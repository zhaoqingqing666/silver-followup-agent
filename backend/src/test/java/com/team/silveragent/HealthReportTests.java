package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.HealthRecordStore;
import com.team.silveragent.application.HealthReportParser;
import com.team.silveragent.application.HealthReportService;
import com.team.silveragent.application.MemoParser;
import com.team.silveragent.domain.model.AgentTurnResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把健康记录发给家属：页面按钮、助手里一句话、每周一早上的自动小结，
 * 三条路共用 {@link HealthReportService} 的同一份汇总。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-report;DB_CLOSE_DELAY=-1",
        "agent.llm.enabled=false",
        // 测试自己调 sendWeekly 验去重，不要启动补发插进来抢跑
        "health-report.weekly.enabled=false"})
class HealthReportTests {

    @Autowired FollowupAgentService service;
    @Autowired HealthReportService reports;
    @Autowired HealthRecordStore records;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM health_records");
        jdbc.update("DELETE FROM family_notifications");
    }

    /** 记一条实测值；daysAgo>0 表示那是几天前量的（用来验时间窗）。 */
    private void seed(String item, String valueNum, String valueText, String unit, int daysAgo) {
        records.create("user-001", item, valueNum == null ? null : new BigDecimal(valueNum), valueText, unit,
                "测试", "conv-seed", MemoParser.nowInDemoZone().minusDays(daysAgo));
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM family_notifications", Integer.class);
    }

    private String notificationContent() {
        return jdbc.queryForObject("SELECT content FROM family_notifications", String.class);
    }

    @Test
    void sentenceNamesTheWindowAndTheItem() {
        HealthReportParser.ReportIntent intent = HealthReportParser.detect("把这个月的血压发给女儿");

        assertThat(intent).isNotNull();
        assertThat(intent.window()).isEqualTo(HealthReportParser.Window.MONTH);
        assertThat(intent.item()).isEqualTo("血压");
        assertThat(intent.explicit()).isTrue();
    }

    @Test
    void sentencesAboutSomethingElseSentToFamilyAreLeftAlone() {
        // “把复诊安排发给家属”里有“发给”也有“家属”，但说的不是健康记录；
        // 错认的代价是撤不回来的消息，所以宁可漏认
        assertThat(HealthReportParser.detect("把复诊安排发给女儿")).isNull();
        // 只是问数值、没说发出去
        assertThat(HealthReportParser.detect("这个月的血压是多少")).isNull();
        // 没有收件人
        assertThat(HealthReportParser.detect("把健康记录发送出去")).isNull();
    }

    @Test
    void withoutAWindowItFallsBackToAWeekAndSaysSo() {
        HealthReportParser.ReportIntent intent = HealthReportParser.detect("把健康记录发给家属");

        assertThat(intent).isNotNull();
        assertThat(intent.window()).isEqualTo(HealthReportParser.Window.WEEK);
        assertThat(intent.item()).isNull();
        assertThat(intent.explicit()).isFalse();
    }

    @Test
    void summaryCountsAveragesAndShowsRealReadings() {
        seed("血压", "138", "138/86", "mmHg", 0);
        seed("血压", "152", "152/94", "mmHg", 1);
        seed("血压", "126", "126/78", "mmHg", 2);
        seed("血糖", "6.4", "6.4", "mmol/L", 0);
        seed("血糖", "7.1", "7.1", "mmol/L", 1);

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, null, "健康记录");

        // 舒张压只在 value_text 的“138/86”里，不拆就算不出平均的那一半
        assertThat(report.text()).contains("血压 3 次，平均 139/86 mmHg（最高 152/94，最低 126/78）");
        assertThat(report.text()).contains("血糖 2 次，平均 6.8 mmol/L（最高 7.1，最低 6.4）");
        assertThat(report.recordCount()).isEqualTo(5);
    }

    @Test
    void aValueWithoutANumberStillGetsCounted() {
        seed("血压", null, "有点高", "mmHg", 0);

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, "血压", "健康记录");

        assertThat(report.text()).contains("血压 1 次（未写具体数值）");
    }

    @Test
    void recordsOlderThanTheWindowAreLeftOut() {
        seed("血压", "138", "138/86", "mmHg", 0);
        seed("血压", "190", "190/100", "mmHg", 40);   // 40 天前，最近一周里没有它

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, "血压", "健康记录");

        assertThat(report.text()).contains("血压 1 次，138/86");
        assertThat(report.text()).doesNotContain("190");
    }

    @Test
    void sendingWritesOneNotificationToThePrimaryContact() {
        seed("血压", "138", "138/86", "mmHg", 0);

        HealthReportService.SendResult result = reports.send("conv-1", "user-001",
                HealthReportParser.Window.WEEK, null, "健康记录");

        assertThat(result.sent()).isTrue();
        assertThat(result.contactLabel()).isEqualTo("女儿 小丽");
        assertThat(notificationCount()).isEqualTo(1);
        assertThat(notificationContent()).contains("血压 1 次，138/86");
    }

    @Test
    void nothingIsSentWhenThereIsNothingToSend() {
        HealthReportService.SendResult result = reports.send("conv-2", "user-001",
                HealthReportParser.Window.WEEK, null, "健康记录");

        assertThat(result.sent()).isFalse();
        assertThat(result.reason()).contains("没有");
        assertThat(notificationCount()).isZero();
    }

    @Test
    void withoutAFamilyContactNothingIsSent() {
        seed("血压", "138", "138/86", "mmHg", 0);
        jdbc.update("DELETE FROM family_contacts");
        try {
            HealthReportService.SendResult result = reports.send("conv-3", "user-001",
                    HealthReportParser.Window.WEEK, null, "健康记录");

            assertThat(result.sent()).isFalse();
            assertThat(result.reason()).contains("家属联系人");
            assertThat(notificationCount()).isZero();
        } finally {
            jdbc.update("INSERT INTO family_contacts(id,user_id,name,relationship,phone)"
                    + " VALUES ('family-001','user-001','小丽','女儿','13800001234')");
        }
    }

    @Test
    void theWeeklyReportGoesOutOncePerWeek() {
        seed("血压", "138", "138/86", "mmHg", 0);
        assertThat(reports.weeklySentThisWeek("user-001")).isFalse();

        assertThat(reports.sendWeekly("user-001").sent()).isTrue();
        // 后端每次启动都会补发一次；本周已经发过就该被挡住
        assertThat(reports.weeklySentThisWeek("user-001")).isTrue();
        reports.sendWeekly("user-001");

        assertThat(notificationCount()).isEqualTo(1);
        assertThat(notificationContent()).startsWith(HealthReportService.WEEKLY_TITLE);
    }

    @Test
    void theAssistantSendsItOnOneSentence() {
        AgentTurnResponse start = service.start("user-001");
        service.chat(start.conversationId(), "我的血压是138/86");
        service.chat(start.conversationId(), "血糖6.4");

        AgentTurnResponse reply = service.chat(start.conversationId(), "把这个月的血压发给女儿");

        // 发出去撤不回来，所以回复里要把发过去的那段原样念一遍
        assertThat(reply.reply()).contains("已把血压记录发给", "女儿", "小丽", "138/86");
        assertThat(reply.reply()).doesNotContain("医院");
        // 只点名了血压，血糖不能跟着一起发
        assertThat(notificationContent()).contains("血压 1 次");
        assertThat(notificationContent()).doesNotContain("血糖");
    }

    @Test
    void theAssistantSaysWhichWindowItAssumed() {
        AgentTurnResponse start = service.start("user-001");
        service.chat(start.conversationId(), "血糖6.4");

        AgentTurnResponse reply = service.chat(start.conversationId(), "把健康记录发给家属");

        // 没说不代表可以替老人拿主意：按一周算，但要讲出来，改起来就一句话
        assertThat(reply.reply()).contains("最近一周算的", "已把健康记录发给");
    }
}
