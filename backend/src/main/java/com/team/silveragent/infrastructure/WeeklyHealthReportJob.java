package com.team.silveragent.infrastructure;

import com.team.silveragent.application.HealthReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每周一次的健康小结：把老人最近一周的记录汇总好发给家属。
 *
 * <p>触发有两处，一主一补：
 * <ul>
 *   <li>{@link #sendWeeklyReports()}——定时，默认周一早上八点（cron 在 application.yml 里）；</li>
 *   <li>{@link #catchUpOnStartup()}——后端每次启动时补一次。</li>
 * </ul>
 *
 * <p>补发是必须的，不是保险起见：定时任务是**进程内**的，周一八点后端没在跑那一次就直接跳过了，
 * Spring 不会替谁记着、也不会事后补回来。而这个项目里后端本来就是用时才起，周一早上大概率没开。
 * 所以改成“一周之内只要后端起来过就发得出去”。是不是本周已经发过，由
 * {@link HealthReportService#weeklySentThisWeek(String)} 判断，所以同一周重启多少次都只发一条。
 *
 * <p>真正的产品该用持久化的调度（Quartz 之类），把“该发没发”落库；这里是演示，够用就好。
 */
@Component
public class WeeklyHealthReportJob {
    private static final Logger log = LoggerFactory.getLogger(WeeklyHealthReportJob.class);

    private final HealthReportService reports;
    private final boolean enabled;

    public WeeklyHealthReportJob(HealthReportService reports,
                                 @Value("${health-report.weekly.enabled:true}") boolean enabled) {
        this.reports = reports;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${health-report.weekly.cron:0 0 8 * * MON}",
            zone = "${health-report.weekly.zone:Asia/Shanghai}")
    public void sendWeeklyReports() {
        run("定时");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        run("启动补发");
    }

    private void run(String trigger) {
        if (!enabled) return;
        try {
            for (String userId : reports.elderUserIdsWithFamily()) {
                if (reports.weeklySentThisWeek(userId)) continue;
                HealthReportService.SendResult result = reports.sendWeekly(userId);
                if (result.sent()) {
                    log.info("[{}]已给 {} 发送本周健康小结（{}，{} 条记录）",
                            trigger, userId, result.rangeLabel(), result.recordCount());
                } else {
                    // 这周没量过就没得发。不发“本周无记录”那种空话，免得家属每周收一条没用的。
                    log.info("[{}]{} 本周没有小结可发：{}", trigger, userId, result.reason());
                }
            }
        } catch (RuntimeException error) {
            // 补发失败不能把整个应用带崩：周报是锦上添花，不是启动的必要条件
            log.warn("[{}]本周健康小结没有发成", trigger, error);
        }
    }
}
