package com.team.silveragent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.application.FollowupAgentService;
import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.service.DashScopeHttp;
import com.team.silveragent.service.VlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片走对话：识图结论进上下文、附件与结论落库、无视觉模型时走友好分支。
 *
 * <p>这里用一个假的 {@link VlService} 顶掉真实调用，测试因此不依赖百炼 key，
 * 也不会因为网络抖动而失败；要验的是「链路怎么接」，不是「模型看得准不准」。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:silver-agent-vision;DB_CLOSE_DELAY=-1",
        "agent.model.enabled=false",
        "agent.vl.enabled=true"})
class MultimodalImageTurnTests {
    /** 一张极小的 1x1 JPEG data URL，够用来验证落库与去重，不占体积。 */
    private static final String TINY_JPEG = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAg=";
    private static final String TINY_JPEG_2 = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAh=";

    @Autowired FollowupAgentService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeVlService vl;

    @TestConfiguration
    static class FakeVisionConfig {
        @Bean
        @Primary
        FakeVlService fakeVlService() { return new FakeVlService(); }
    }

    /** 顶掉真实视觉模型的替身：记录调用次数，返回固定结论。 */
    static class FakeVlService extends VlService {
        final AtomicInteger recognizeCalls = new AtomicInteger();
        boolean enabled = true;

        FakeVlService() {
            super(true, "http://127.0.0.1:1", "qwen3-vl-plus", "fake-key",
                    500, 1000, 0, new ObjectMapper(), new DashScopeHttp(new ObjectMapper()));
        }

        @Override public boolean isEnabled() { return enabled; }

        @Override public List<VisionResult> recognizeAll(List<String> imageDataUrls, String userHint) {
            recognizeCalls.incrementAndGet();
            return imageDataUrls.stream()
                    .map(url -> new VisionResult("看起来是一盒降压药。", "苯磺酸氨氯地平片 5mg",
                            "- 苯磺酸氨氯地平片 5mg"))
                    .toList();
        }
    }

    @BeforeEach
    void reset() {
        vl.enabled = true;
        vl.recognizeCalls.set(0);
        jdbc.update("DELETE FROM vision_results");
        jdbc.update("DELETE FROM conversation_attachments");
        jdbc.update("DELETE FROM conversation_messages");
        jdbc.update("DELETE FROM tool_call_logs");
    }

    @Test
    void imageWithoutVisionServiceReturnsFriendlyMessageAndKeepsStage() {
        String id = service.start().conversationId();
        String stageBefore = service.resume(id).stage();
        vl.enabled = false;

        AgentTurnResponse turn = service.handleImages(id, List.of(TINY_JPEG), "这是什么药");

        assertThat(turn.reply()).contains("图片识别功能暂时没有开启");
        assertThat(turn.stage()).isEqualTo(stageBefore);
        // 图片本身仍然留痕，老人不用重拍；但一次视觉调用都不该发生。
        assertThat(count("conversation_attachments")).isEqualTo(1);
        assertThat(vl.recognizeCalls).hasValue(0);
        assertThat(count("vision_results")).isZero();
    }

    @Test
    void imageStoresAttachmentMessageAndVisionResult() {
        String id = service.start().conversationId();

        AgentTurnResponse turn = service.handleImages(id, List.of(TINY_JPEG), "这是什么药");

        // 主模型没启用，只如实复述识别结论，不编造解读。
        assertThat(turn.reply()).contains("看起来是一盒降压药");
        assertThat(count("conversation_attachments")).isEqualTo(1);
        assertThat(count("vision_results")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id=? AND message_type='IMAGE'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_call_logs WHERE conversation_id=? AND tool_name='vision.recognize'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM vision_results WHERE conversation_id=?",
                String.class, id)).contains("看起来是一盒降压药", "5mg");
    }

    @Test
    void duplicateImagesAreRecognizedOnceOnly() {
        String id = service.start().conversationId();

        service.handleImages(id, List.of(TINY_JPEG, TINY_JPEG), "");

        // 同一张图重复上传只存一份、只识别一次。
        assertThat(count("conversation_attachments")).isEqualTo(1);
        assertThat(count("vision_results")).isEqualTo(1);
    }

    @Test
    void imagesOverTheLimitAreTruncated() {
        String id = service.start().conversationId();

        service.handleImages(id, List.of(TINY_JPEG, TINY_JPEG_2, TINY_JPEG + "x", TINY_JPEG + "y", TINY_JPEG + "z"), "");

        assertThat(count("conversation_attachments")).isEqualTo(3);
    }

    @Test
    void blankImageListIsPolitelyRejected() {
        String id = service.start().conversationId();

        AgentTurnResponse turn = service.handleImages(id, List.of("  "), "");

        assertThat(turn.reply()).contains("没有收到图片");
        assertThat(count("conversation_attachments")).isZero();
        assertThat(vl.recognizeCalls).hasValue(0);
    }

    @Test
    void followUpQuestionReusesStoredVisionWithoutCallingVisionAgain() {
        String id = service.start().conversationId();
        service.handleImages(id, List.of(TINY_JPEG), "这是什么药");
        assertThat(vl.recognizeCalls).hasValue(1);

        // 追问一句和图片有关的普通问题：不该重新识别，但会话上下文里仍然带着上一次的结论。
        AgentTurnResponse followUp = service.chat(id, "刚才那张图里的药叫什么");

        assertThat(vl.recognizeCalls).as("追问不得重复调用视觉模型").hasValue(1);
        assertThat(followUp).isNotNull();
    }

    @Test
    void readAloudReturnsTheRawOcrWithoutModel() {
        String id = service.start().conversationId();
        service.handleImages(id, List.of(TINY_JPEG), "这是什么药");

        AgentTurnResponse turn = service.chat(id, "把上面的字念一遍");

        // 逐字照抄的问题不走语言模型，避免规格被改写。
        assertThat(turn.reply()).isEqualTo("苯磺酸氨氯地平片 5mg");
        assertThat(turn.speechText()).isEqualTo("苯磺酸氨氯地平片 5mg");
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
