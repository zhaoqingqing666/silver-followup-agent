package com.team.silveragent.infrastructure.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.silveragent.domain.model.ToolModels.DrugKnowledge;
import com.team.silveragent.domain.tool.DrugKnowledgeTool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品知识库的真实检索实现：知识库放在 {@code resources/drug-knowledge.json}，
 * 每次查询都对它做一次真实匹配，命中哪条返回哪条，<b>不做任何编造</b>。
 *
 * <p>查询过程照常写入 tool_call_logs，所以前端能看到真实的调用参数与真实结果 ——
 * 这一点对比赛演示很关键：药品说明不是写死在回复模板里的。
 */
@Component
public class MockDrugKnowledgeTool implements DrugKnowledgeTool {
    /** 太宽泛的词不作为匹配依据，避免「片」字命中整库。 */
    private static final List<String> GENERIC_WORDS = List.of(
            "药", "片", "胶囊", "颗粒", "丸", "口服液", "缓释片", "控释片", "肠溶片", "滴丸", "药品", "药片");

    /** 去掉剂型后再匹配一次：「二甲双胍」要能命中「盐酸二甲双胍缓释片」。 */
    private static final List<String> DOSAGE_FORMS = List.of(
            "缓释片", "控释片", "肠溶片", "滴丸", "片", "胶囊", "颗粒", "丸");

    /** 一次最多返回几条：说太多老人记不住，也只够一次回复用。 */
    private static final int MAX_RESULTS = 3;

    private final List<DrugKnowledge> catalog;
    private final ToolTraceStore traces;

    public MockDrugKnowledgeTool(ObjectMapper json, ToolTraceStore traces) {
        this.traces = traces;
        this.catalog = loadCatalog(json);
    }

    @Override
    public List<DrugKnowledge> search(String conversationId, String drugName, String specification) {
        List<DrugKnowledge> result = lookup(drugName);
        Map<String, String> request = new LinkedHashMap<>();
        request.put("drug_name", drugName == null ? "" : drugName);
        if (specification != null && !specification.isBlank()) request.put("specification", specification);
        traces.record(conversationId, "drug.queryKnowledge", request, result, true);
        return result;
    }

    /** 名称 / 别名双向包含匹配；能命中别名说明老人用的是俗称，同样返回整条知识。 */
    private List<DrugKnowledge> lookup(String drugName) {
        if (drugName == null || drugName.isBlank()) return List.of();
        String query = normalize(drugName);
        // 一个字或「药」「片」这种泛词不能作为查询依据，否则会把整库都倒出来。
        if (query.length() < 2 || GENERIC_WORDS.contains(query)) return List.of();

        List<DrugKnowledge> hits = new ArrayList<>();
        for (DrugKnowledge drug : catalog) {
            for (String key : matchKeys(drug)) {
                String candidate = normalize(key);
                if (candidate.length() < 2 || GENERIC_WORDS.contains(candidate)) continue;
                if (candidate.contains(query) || query.contains(candidate)) {
                    hits.add(drug);
                    break;
                }
            }
            if (hits.size() >= MAX_RESULTS) break;
        }
        return List.copyOf(hits);
    }

    /** 一条知识参与匹配的写法：药名 + 商品名/简称别名 + 去掉剂型后的药名。 */
    private List<String> matchKeys(DrugKnowledge drug) {
        List<String> keys = new ArrayList<>();
        keys.add(drug.name());
        if (drug.aliases() != null) keys.addAll(drug.aliases());
        String base = drug.name();
        if (base != null) {
            for (String form : DOSAGE_FORMS) {
                if (base.endsWith(form) && base.length() > form.length() + 1) {
                    keys.add(base.substring(0, base.length() - form.length()));
                    break;
                }
            }
        }
        return keys;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    /** 知识库读取失败不阻塞启动：查询返回空列表，由上层如实告诉老人「没查到」。 */
    private List<DrugKnowledge> loadCatalog(ObjectMapper json) {
        try (InputStream in = new ClassPathResource("drug-knowledge.json").getInputStream()) {
            JsonNode root = json.readTree(in);
            List<DrugKnowledge> items = new ArrayList<>();
            for (JsonNode node : root) {
                List<String> aliases = new ArrayList<>();
                for (JsonNode alias : node.path("aliases")) aliases.add(alias.asText());
                items.add(new DrugKnowledge(
                        node.path("name").asText(),
                        node.path("specification").asText(),
                        node.path("category").asText(),
                        node.path("purpose").asText(),
                        node.path("reminder").asText(),
                        node.path("followupTip").asText(),
                        List.copyOf(aliases)));
            }
            return List.copyOf(items);
        } catch (Exception error) {
            return List.of();
        }
    }
}
