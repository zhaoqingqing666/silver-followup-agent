package com.team.silveragent.api;

import com.team.silveragent.application.health.HealthReportParser;
import com.team.silveragent.application.health.HealthReportService;
import com.team.silveragent.domain.model.ToolModels.Contact;
import org.springframework.web.bind.annotation.*;

/**
 * 老人端“健康记录”页上的两件事：发给谁、发什么。
 *
 * <p>汇总和发送都在 {@link HealthReportService}，和助手里那句“把这个月的血压发给女儿”、
 * 每周一早上的自动小结是同一条路——页面只是换个入口，不另写一份汇总逻辑。
 */
@RestController
@RequestMapping("/api/users/{userId}")
public class HealthReportController {
    /**
     * 页面上手动发时用的会话 id。故意写死：{@code family_notifications} 的 id 由
     * “会话 + 联系人 + 内容”算出来，同样的内容不会重复入库——老人手抖点两下
     * 家属只会收到一条。内容里的日期范围每天都变，所以第二天还能再发。
     */
    private static final String PAGE_SESSION = "health-report";

    private final HealthReportService reports;

    public HealthReportController(HealthReportService reports) { this.reports = reports; }

    /** 主联系人，给页面上的按钮显示“发给女儿 小丽”用；没配家属时 contact 为 null。 */
    @GetMapping("/family-contact")
    public FamilyContactResponse familyContact(@PathVariable("userId") String userId) {
        return new FamilyContactResponse(reports.primaryContact(PAGE_SESSION, userId));
    }

    /**
     * 把健康记录汇总后发给家属。
     *
     * @param range week=最近一周（默认），month=最近一个月。
     * @param item  只发某一个项目（“血压”）；不传表示全部。
     */
    @PostMapping("/health-report")
    public HealthReportResponse send(@PathVariable("userId") String userId,
                                     @RequestParam(name = "range", defaultValue = "week") String range,
                                     @RequestParam(name = "item", required = false) String item) {
        HealthReportParser.Window window = "month".equalsIgnoreCase(range)
                ? HealthReportParser.Window.MONTH : HealthReportParser.Window.WEEK;
        HealthReportService.SendResult result = reports.send(PAGE_SESSION, userId, window,
                item == null || item.isBlank() ? null : item.trim(), "健康记录");
        return new HealthReportResponse(result.sent(), result.reason(), result.contactLabel(),
                result.rangeLabel(), result.recordCount(), result.message());
    }

    /** 电话是后端脱敏后的掩码，不含明文。 */
    public record FamilyContactResponse(Contact contact) { }

    /**
     * @param message 真发出去的那段话（页面可以原样给老人看一遍：发了什么，心里有数）。
     * @param reason  没发出去时的原因，能直接念给老人听。
     */
    public record HealthReportResponse(boolean sent, String reason, String contactLabel,
                                       String rangeLabel, int recordCount, String message) { }
}
