package com.team.silveragent.infrastructure.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.domain.model.ToolModels.DrugKnowledge;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 药品检索是「我们这边的真实数据」而不是模型的记忆：命中哪条必须能追溯到 drug-knowledge.json 里的原文。
 * 这里逐条锁住检索规则，尤其是泛词过滤 —— 一旦「片」被当成查询词，整库都会倒出来。
 */
class MockDrugKnowledgeToolTests {
    private final ToolTraceStore traces = mock(ToolTraceStore.class);
    private final MockDrugKnowledgeTool tool = new MockDrugKnowledgeTool(new ObjectMapper(), traces);

    @Test
    void exactFullNameHitsTheMatchingEntry() {
        List<DrugKnowledge> hits = tool.search("c1", "盐酸二甲双胍缓释片", null);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("盐酸二甲双胍缓释片");
        assertThat(hits.get(0).category()).isEqualTo("降糖药");
        assertThat(hits.get(0).reminder()).isNotBlank();
    }

    /** 双向 contains 的两个方向：老人只说药名片段，或把药名夹在一句话里，都要能命中。 */
    @Test
    void partialNameAndNameInsideALongerSentenceBothHit() {
        assertThat(tool.search("c1", "阿卡波糖", null))
                .extracting(DrugKnowledge::name).containsExactly("阿卡波糖片");
        assertThat(tool.search("c1", "阿司匹林肠溶片什么时候吃", null))
                .extracting(DrugKnowledge::name).containsExactly("阿司匹林肠溶片");
    }

    /** 商品名/俗称也是真实匹配依据：「格华止」「拜阿司匹灵」都得查到。 */
    @Test
    void brandAliasesHitTheSameEntry() {
        assertThat(tool.search("c1", "格华止", null))
                .extracting(DrugKnowledge::name).containsExactly("盐酸二甲双胍缓释片");
        assertThat(tool.search("c1", "拜阿司匹灵", null))
                .extracting(DrugKnowledge::name).containsExactly("阿司匹林肠溶片");
    }

    /** 剂型可省略：「氨氯地平」要能命中「苯磺酸氨氯地平片」。 */
    @Test
    void nameWithoutDosageFormStillHits() {
        assertThat(tool.search("c1", "氨氯地平", null))
                .extracting(DrugKnowledge::name).containsExactly("苯磺酸氨氯地平片");
    }

    /** 泛词与单字不能作为匹配依据，否则一次查询就把整库倒出来，回复也没法用。 */
    @Test
    void genericWordsAndTooShortQueriesReturnNothing() {
        for (String query : List.of("药", "片", "胶囊", "药片", "颗粒", "o")) {
            assertThat(tool.search("c1", query, null)).as(query).isEmpty();
        }
    }

    @Test
    void blankAndNullQueriesReturnNothing() {
        assertThat(tool.search("c1", null, null)).isEmpty();
        assertThat(tool.search("c1", "   ", null)).isEmpty();
    }

    @Test
    void unknownDrugReturnsNothing() {
        assertThat(tool.search("c1", "阿莫西林", null)).isEmpty();
    }

    /** 一次最多 3 条：说太多老人记不住，超过的按知识库顺序截断。 */
    @Test
    void atMostThreeEntriesAreReturned() {
        List<DrugKnowledge> hits = tool.search("c1",
                "我在吃阿卡波糖片、盐酸二甲双胍缓释片、苯磺酸氨氯地平片和阿托伐他汀钙片", null);

        assertThat(hits).hasSize(3);
        assertThat(hits).extracting(DrugKnowledge::name).contains("阿卡波糖片");
    }

    /** 规格只是记录下来的查询条件，不参与匹配，免得老人漏说规格就查不到。 */
    @Test
    void specificationDoesNotNarrowTheMatch() {
        assertThat(tool.search("c1", "阿司匹林", "100mg"))
                .extracting(DrugKnowledge::name).containsExactly("阿司匹林肠溶片");
    }

    /** 查没查到都要留痕：前端要能看到「真查了，知识库里确实没有」而不是模型随口答的。 */
    @Test
    void everyQueryIsRecordedInTheToolTraceEvenWhenNothingMatches() {
        tool.search("c-drug", "阿莫西林", null);

        verify(traces).record(eq("c-drug"), eq("drug.queryKnowledge"), any(), any(), eq(true));
    }

    /** 知识库读取失败不能让应用起不来：构造不抛，查询返回空，由上层如实告诉老人「没查到」。 */
    @Test
    void missingCatalogFileDoesNotBreakStartup() {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        ClassLoader withoutResources = new URLClassLoader(new URL[0], null);
        Thread.currentThread().setContextClassLoader(withoutResources);
        try {
            assertThatCode(() -> new MockDrugKnowledgeTool(new ObjectMapper(), traces))
                    .doesNotThrowAnyException();
            assertThat(new MockDrugKnowledgeTool(new ObjectMapper(), traces).search("c1", "阿司匹林", null))
                    .isEmpty();
        } finally {
            Thread.currentThread().setContextClassLoader(parent);
        }
    }
}
