package com.team.silveragent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.agent.planning.PlannerToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实时进度是评审判断「智能体真在调工具」的唯一凭据，所以它自己的行为也要锁住：
 * 序号必须单调（前端靠增量拉取）、进行中必须能收尾、参数必须脱敏。
 */
class TurnProgressTests {

    private final TurnProgress progress = new TurnProgress(new ObjectMapper());

    @Test
    void marksUnderstandingOnBeginAndReportsActive() {
        progress.begin("c1");
        TurnProgress.Snapshot snapshot = progress.snapshot("c1", 0);
        assertTrue(snapshot.active());
        assertEquals(1, snapshot.events().size());
        assertEquals(TurnProgress.Kind.UNDERSTANDING, snapshot.events().get(0).kind());
    }

    /** 识图轮会再调一次 chatInternal；那次 begin 不能把已记下的识图事件抹掉。 */
    @Test
    void secondBeginDuringSameTurnKeepsEvents() {
        progress.begin("c1");
        progress.toolResult("c1", "vision.recognize", "{}", "{\"recognized\":1}", true);
        progress.begin("c1");
        TurnProgress.Snapshot snapshot = progress.snapshot("c1", 0);
        assertEquals(2, snapshot.events().size());
        assertEquals("vision.recognize", snapshot.events().get(1).tool());
    }

    @Test
    void endStopsReportingActive() {
        progress.begin("c1");
        progress.end("c1");
        TurnProgress.Snapshot snapshot = progress.snapshot("c1", 0);
        assertFalse(snapshot.active());
        // 事件保留：前端可能在响应到达前最后一次拉到收尾状态。
        assertEquals(1, snapshot.events().size());
    }

    /** 一轮结束后再 begin 要能开新的一轮，而不是接着上一轮往后编号。 */
    @Test
    void beginAfterEndStartsFreshTurn() {
        progress.begin("c1");
        progress.end("c1");
        progress.begin("c1");
        TurnProgress.Snapshot snapshot = progress.snapshot("c1", 0);
        assertEquals(1, snapshot.events().size());
        assertEquals(1, snapshot.events().get(0).seq());
    }

    @Test
    void afterSeqReturnsOnlyNewerEvents() {
        progress.begin("c1");
        progress.toolProposed("c1", List.of(new PlannerToolCall("appointment.querySlots",
                Map.of("hospital", "h001", "date", "2026-09-18"))));
        TurnProgress.Snapshot snapshot = progress.snapshot("c1", 1);
        assertEquals(1, snapshot.events().size());
        assertEquals(TurnProgress.Kind.TOOL_PROPOSED, snapshot.events().get(0).kind());
        assertEquals(2, snapshot.events().get(0).seq());
    }

    /** 模型真实生成的参数要原样带出去——这是「参数由智能体生成」的证据本身。 */
    @Test
    void proposedToolCarriesModelGeneratedArguments() {
        progress.begin("c1");
        progress.toolProposed("c1", List.of(new PlannerToolCall("careGuide.search",
                Map.of("query", "复诊要带什么"))));
        TurnProgress.Event event = progress.snapshot("c1", 1).events().get(0);
        assertEquals("careGuide.search", event.tool());
        assertTrue(event.parameters().contains("复诊要带什么"));
    }

    /** 手机号按全项目约定脱敏，不能让它顺着这个只读端点漏到页面上。 */
    @Test
    void masksPhoneNumbers() {
        progress.begin("c1");
        progress.toolResult("c1", "family.notify", "{\"phone\":\"13812341234\"}", "{}", true);
        String parameters = progress.snapshot("c1", 1).events().get(0).parameters();
        assertEquals("{\"phone\":\"138****1234\"}", parameters);
    }

    /** 图片 base64 有几万字符，既不安全也没有展示价值，必须换掉。 */
    @Test
    void masksImageDataUrls() {
        String dataUrl = "data:image/jpeg;base64," + "A".repeat(5000);
        progress.begin("c1");
        progress.toolResult("c1", "vision.recognize", "{\"image\":\"" + dataUrl + "\"}", "{}", true);
        String parameters = progress.snapshot("c1", 1).events().get(0).parameters();
        assertFalse(parameters.contains("AAAA"));
        assertTrue(parameters.contains("图片内容已省略"));
    }

    /** 结果再长也不能把面板撑爆。 */
    @Test
    void truncatesVeryLongFields() {
        progress.begin("c1");
        progress.toolResult("c1", "catalog.queryHospitals", "{}", "{\"blob\":\"" + "z".repeat(3000) + "\"}",
                true);
        String result = progress.snapshot("c1", 1).events().get(0).result();
        assertTrue(result.length() < 1000, "结果应该被截断，实际长度 " + result.length());
    }

    @Test
    void unknownConversationIsInactiveAndEmpty() {
        TurnProgress.Snapshot snapshot = progress.snapshot("never-seen", 0);
        assertFalse(snapshot.active());
        assertTrue(snapshot.events().isEmpty());
    }
}
