package com.team.silveragent;

import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.application.health.HealthRecordStore;
import com.team.silveragent.application.health.HealthReportParser;
import com.team.silveragent.application.health.HealthReportService;
import com.team.silveragent.application.memo.MemoParser;
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
        "agent.model.enabled=false",
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

    /** 从对话里报一个数并点头确认（实测数值要过确认卡才落库）。 */
    private void reportInChat(String conversationId, String sentence) {
        AgentTurnResponse ask = service.chat(conversationId, sentence);
        assertThat(ask.confirmation()).as(ask.reply()).isNotNull();
        service.confirm(conversationId, true, ask.confirmation().confirmationId());
    }

    private int notificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM family_notifications", Integer.class);
    }

    private String notificationContent() {
        return jdbc.queryForObject("SELECT content FROM family_notifications", String.class);
    }

    @Test
    void aHalfBloodPressureReadingIsNotCountedAsSystolic() {
        // 只说了一半的“低压95”：解析器把“低压”两个字标在值前面（见 DIASTOLIC_MARK），
        // 汇总里不能把它当收缩压——那会算出“平均 117/86”这种谁也没量到过的血压发给家属。
        seed("血压", "138", "138/86", "mmHg", 1);
        seed("血压", "95", "低压95", "mmHg", 1);

        HealthReportService.Report report =
                reports.summarize("user-001", HealthReportParser.Window.WEEK, "血压", "健康记录");

        // 收缩压只有 138 这一次；舒张压是 86 和 95 的平均（90.5 → 91）
        assertThat(report.text()).contains("平均 138/91");
        assertThat(report.text()).doesNotContain("117");
    }

    @Test
    void aWindowWithOnlyDiastolicReadingsIsLabelledInsteadOfReportedAsSystolic() {
        seed("血压", "95", "低压95", "mmHg", 1);

        String text = reports.summarize("user-001", HealthReportParser.Window.WEEK, "血压", "健康记录").text();

        // 直接报“95 mmHg”会被当成收缩压（那是个低到危险的值），标出这是低压
        assertThat(text).contains("低压 95");
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
    void weightSaidInJinIsNotAveragedTogetherWithKilograms() {
        // 190 斤和 95 公斤是同一个重量，只是老人换了个说法
        seed("体重", "190", "190", "斤", 0);
        seed("体重", "95", "95", "kg", 1);

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, "体重", "健康记录");

        assertThat(report.text()).contains("体重 2 次，平均 95 kg");
        // 142 是 190 和 95 直接平均出来的数——没人量到过它，不能发到家属手上
        assertThat(report.text()).doesNotContain("142");
    }

    @Test
    void theOtherBucketIsListedByItsOwnWordsInsteadOfAveraged() {
        // 「其他」是认不出项目的兜底桶：尿酸、步数、身高都落在这里，彼此没有量纲关系。
        // 而且解析器给「其他」存的单位是空串，算出来的数连个量纲都挂不上。
        seed("其他", "5000", "我今天走了5000步", "", 1);
        seed("其他", "420", "我尿酸420", "", 1);
        seed("血压", "138", "138/86", "mmHg", 1);

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, null, "健康记录");

        // 列原话，家属自己看得懂；“平均 2710”是“5000 步”和“尿酸 420”凑出来的，谁也没量到过
        assertThat(report.text()).contains("其他 2 次：", "我今天走了5000步", "我尿酸420");
        assertThat(report.text()).doesNotContain("2710", "最高", "平均");
        // 正经项目照旧走平均，别被这一条带歪
        assertThat(report.text()).contains("血压 1 次，138/86 mmHg");
    }

    @Test
    void aLongOtherBucketStillLeavesRoomForTheRealItems() {
        // 一条原话最长能到 60 字，一个月攒下来能把整条消息顶过 family_notifications.content
        // 的 500 字上限——那样连结尾的“等 N 项未列出”都送不出去，所以「其他」自己先收口
        for (int index = 0; index < 12; index++) {
            seed("其他", String.valueOf(100 + index),
                    "我今天感觉还可以就是腿有点酸走了" + (100 + index) + "步不想再出门了", "", 1);
        }
        seed("血压", "138", "138/86", "mmHg", 1);

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, null, "健康记录");

        assertThat(report.text()).contains("其他 12 次（只列最近", "血压 1 次，138/86 mmHg");
        assertThat(report.text().length()).isLessThan(500);
    }

    @Test
    void aReadingWithoutAUnitDoesNotStripTheUnitFromTheWholeLine() {
        // 最新那条是口语条（“我血压有点高”）：没有数，也没有单位
        seed("血压", "138", "138/86", "mmHg", 1);
        seed("血压", null, "有点高", "", 0);

        HealthReportService.Report report = reports.summarize("user-001", HealthReportParser.Window.WEEK, "血压", "健康记录");

        // 单位取自那条真量过的；拿最新那条（没单位）去定，整行的 mmHg 就没了
        assertThat(report.text()).contains("血压 2 次（另有 1 次未写数值），138/86 mmHg");
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
        reportInChat(start.conversationId(), "我的血压是138/86");
        reportInChat(start.conversationId(), "血糖6.4");

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
        reportInChat(start.conversationId(), "血糖6.4");

        AgentTurnResponse reply = service.chat(start.conversationId(), "把健康记录发给家属");

        // 没说不代表可以替老人拿主意：按一周算，但要讲出来，改起来就一句话
        assertThat(reply.reply()).contains("最近一周算的", "已把健康记录发给");
    }
}
