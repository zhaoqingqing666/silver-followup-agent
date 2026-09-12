package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import com.team.silveragent.domain.model.ToolModels.HospitalProfile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将老人口语里的医院、科室简称与真实目录候选对齐。
 * 这里只做候选解析，不写会话状态；存在性始终以目录工具返回的数据为准。
 */
@Component
final class CatalogEntityResolver {
    enum MatchType { EXACT, UNIQUE_APPROXIMATE, AMBIGUOUS, NOT_FOUND, UNCLEAR }

    record Match(MatchType type, String raw, List<Candidate> candidates) {
        Candidate only() { return candidates.size() == 1 ? candidates.get(0) : null; }
    }

    record Candidate(String id, String name) { }

    private static final Map<String, String> HOSPITAL_ALIASES = aliases(
            "市一", "市第一医院", "一院", "市第一医院", "第一医院", "市第一医院",
            "人民医院", "市人民医院", "市人民", "市人民医院");
    private static final Map<String, String> DEPARTMENT_ALIASES = aliases(
            "神内", "神经内科", "神经科", "神经内科", "神经内科门诊", "神经内科",
            "心内", "心内科", "心血管内科", "心内科", "心脏内科", "心内科",
            "内分泌", "内分泌科", "内分泌门诊", "内分泌科", "骨科门诊", "骨科");

    Match hospital(String utterance, List<HospitalProfile> rows) {
        String raw = cleanUtterance(utterance, true);
        if (unclear(raw)) return new Match(MatchType.UNCLEAR, raw, List.of());
        return match(raw, rows.stream().map(item -> new Candidate(item.id(), item.name())).toList(),
                HOSPITAL_ALIASES);
    }

    Match department(String utterance, List<DepartmentProfile> rows) {
        String raw = cleanUtterance(utterance, false);
        if (unclear(raw)) return new Match(MatchType.UNCLEAR, raw, List.of());
        return match(raw, rows.stream().map(item -> new Candidate(item.id(), item.name())).toList(),
                DEPARTMENT_ALIASES);
    }

    private Match match(String raw, List<Candidate> rows, Map<String, String> aliases) {
        String query = normalize(raw);
        if (query.isBlank()) return new Match(MatchType.UNCLEAR, raw, List.of());
        List<Candidate> exact = rows.stream().filter(item -> normalize(item.name()).equals(query)).toList();
        if (exact.size() == 1) return new Match(MatchType.EXACT, raw, exact);

        String alias = aliases.get(query);
        if (alias != null) {
            List<Candidate> aliased = rows.stream()
                    .filter(item -> normalize(item.name()).equals(normalize(alias))).toList();
            if (aliased.size() == 1) return new Match(MatchType.UNIQUE_APPROXIMATE, raw, aliased);
        }

        List<Candidate> similar = rows.stream().filter(item -> {
            String name = normalize(item.name());
            return name.contains(query) || query.contains(name);
        }).toList();
        if (similar.size() == 1) return new Match(MatchType.UNIQUE_APPROXIMATE, raw, similar);
        if (similar.size() > 1) return new Match(MatchType.AMBIGUOUS, raw, similar);
        return new Match(MatchType.NOT_FOUND, raw, List.of());
    }

    private String cleanUtterance(String value, boolean hospital) {
        String raw = value == null ? "" : value.trim();
        raw = raw.replaceAll("[，。！？,.!?]", "")
                .replaceAll("^(我|俺)?(想|要|准备|打算|希望)?(去|到|挂|看)?", "")
                .replace("办理复诊", "").replace("预约复诊", "")
                .replace("去复诊", "").replace("复诊", "").replace("就诊", "")
                .replace("医生说", "").replace("医生让我", "").replace("医生叫我", "")
                .replace("我说的是", "").replace("应该是", "").replace("好像是", "")
                .replace("可能是", "")
                .replaceAll("^(那就|那么|那|就)", "")
                .replaceAll("(吧|呢|啊|呀)$", "")
                .trim();
        if (!hospital) raw = raw.replace("挂号", "").replace("挂", "").trim();
        return raw;
    }

    private boolean unclear(String value) {
        return value.isBlank() || containsAny(value, "不知道", "不清楚", "没想好", "忘了", "不记得", "随便");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("（模拟）", "").replace("(模拟)", "")
                .replaceAll("[\\s·・]", "").toLowerCase();
    }

    private boolean containsAny(String value, String... words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static Map<String, String> aliases(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return Map.copyOf(result);
    }
}
