package com.team.silveragent.agent.planning;

import com.team.silveragent.agent.AgentContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 识图片段只在真的有识图记录时才出现。
 *
 * <p>这条边界很重要：纯文字会话的提示词必须与加这个功能之前<b>逐字相同</b>，
 * 既有的一批提示词断言全部建立在那份文本上。
 */
class AgentSystemPromptVisionTests {
    private final AgentSystemPrompt prompt = new AgentSystemPrompt();

    private AgentContext context(List<AgentContext.VisionNote> vision) {
        return new AgentContext("STARTED", "用户=user-001", LocalDate.of(2026, 9, 11),
                List.of(), AgentContext.Identity.SELF, vision);
    }

    @Test
    void noVisionMeansNoVisionSection() {
        String text = prompt.planning(context(List.of()), "[]");

        assertThat(text).doesNotContain("图片识别结果", "图片上的文字");
    }

    @Test
    void visionSectionCarriesDescriptionAndOcr() {
        String text = prompt.planning(context(List.of(new AgentContext.VisionNote(
                "这是什么药", "看起来是一盒降压药。", "苯磺酸氨氯地平片 5mg", null))), "[]");

        assertThat(text).contains("本会话最近的图片识别结果", "看起来是一盒降压药", "苯磺酸氨氯地平片 5mg");
        // 必须明确禁止模型改写图片上的数字与规格。
        assertThat(text).contains("不得凭记忆改写");
    }

    @Test
    void ocrIsTruncatedSoItCannotFloodThePrompt() {
        String huge = "阿".repeat(5000);

        String text = prompt.planning(context(List.of(new AgentContext.VisionNote(
                null, "一盒药", huge, null))), "[]");

        assertThat(text).contains("后略");
        assertThat(text).doesNotContain("阿".repeat(1600));
    }

    @Test
    void blankVisionNoteAddsNothing() {
        String text = prompt.planning(context(List.of(new AgentContext.VisionNote(
                null, "  ", "", null))), "[]");

        assertThat(text).doesNotContain("图片识别结果");
    }

    @Test
    void fourArgConstructorStillHasNoVision() {
        AgentContext legacy = new AgentContext("STARTED", "", LocalDate.of(2026, 9, 11), List.of());

        assertThat(legacy.hasVision()).isFalse();
        assertThat(prompt.planning(legacy, "[]")).doesNotContain("图片识别结果");
    }
}
