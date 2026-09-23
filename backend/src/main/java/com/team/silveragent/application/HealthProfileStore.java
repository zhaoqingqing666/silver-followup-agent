package com.team.silveragent.application;

import com.team.silveragent.application.memo.MemoParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 老人健康档案：过敏史、既往病史、身高、体重（当前值）。
 *
 * <p><b>谁能改。</b>老人自己、家属、志愿者都能改。两个端读写的就是这一行——家属端填完，
 * 老人端下次打开就是新的，反过来也一样。所以这里没有、也不需要任何"同步"逻辑：
 * 只有一份数据，就不会有两份对不上的问题。
 *
 * <p><b>谁不能碰：助手。</b>这块数据不接进提示词、也不给任何工具。它不会拿既往病史
 * 给用药建议，也不会把过敏史念给谁听——因为它根本读不到。这不靠提示词里写一句"不许提"，
 * 是结构上就没有那条路（HealthProfileTests 里有一条用例守着它，谁哪天把它接上就会红）。
 *
 * <p><b>和健康记录的分工。</b>这里存的是"现在是多少"，由人填；每一次测量的历史在
 * health_records 里（"9月10日量了50公斤"）。两者可能不一致——家属端那一页会把
 * "最近一次记录"一起显示出来，不一致能当场看见，而不是藏起来让人自己发现。
 */
@Repository
public class HealthProfileStore {
    /** 身高体重的合理区间：挡的是手滑和明显不对的数（体重190、身高18），不追求医学上的精确。 */
    private static final int MIN_HEIGHT_CM = 50;
    private static final int MAX_HEIGHT_CM = 250;
    private static final double MIN_WEIGHT_KG = 20;
    private static final double MAX_WEIGHT_KG = 300;
    private static final int MAX_ALLERGIES = 300;
    private static final int MAX_HISTORY = 500;
    private static final int MAX_EDITOR = 40;

    private final JdbcTemplate jdbc;

    HealthProfileStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 还没填过的老人返回一份空档案，不是 null——前端只判"有没有值"，少一层空判断。 */
    public HealthProfile get(String userId) {
        List<HealthProfile> rows = jdbc.query("""
                SELECT allergies,medical_history,height_cm,weight_kg,
                       health_profile_updated_by,health_profile_updated_at
                FROM users WHERE id=?
                """, (rs, row) -> new HealthProfile(userId,
                rs.getObject(3) == null ? null : rs.getInt(3),
                rs.getObject(4) == null ? null : rs.getDouble(4),
                rs.getString(1), rs.getString(2), rs.getString(5),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime()),
                userId);
        return rows.isEmpty()
                ? new HealthProfile(userId, null, null, null, null, null, null) : rows.get(0);
    }

    /**
     * 保存。四个字段一个口径：<b>null = 这一项没动，空串 = 清掉，有值 = 改成它</b>。
     *
     * <p>身高体重也走字符串，就是为了这个口径能统一——不然"清空身高"没有别的办法表达，
     * 只能让用户把 158 改成 0 这种明显是错的数。填了不是数字的东西会在下面给出人话提示。
     *
     * <p>先全部校验完再落库：不通过就抛出去，一个字都不写——不能出现"身高超范围被拒了，
     * 但过敏史已经覆盖进去"这种半截结果。
     */
    @Transactional
    public HealthProfile save(String userId, String editor, String heightCm, String weightKg,
                              String allergies, String medicalHistory) {
        HealthProfile current = get(userId);
        Integer height = parseHeight(heightCm, current.heightCm());
        Double weight = parseWeight(weightKg, current.weightKg());
        // 先归一成"要么有内容、要么 null"，长度判断才不用到处防 null：
        // 库里本来就是空的、这次又没动这一项（传 null），两边都是 null 是正常情况——
        // 比如还没人填过的档案，老人上来只改了个体重。
        String allergyText = blankToNull(allergies == null ? current.allergies() : allergies);
        String historyText = blankToNull(medicalHistory == null ? current.medicalHistory() : medicalHistory);
        String editorName = blankToNull(editor == null ? current.updatedBy() : editor);

        if (allergyText != null && allergyText.length() > MAX_ALLERGIES) {
            throw new IllegalArgumentException("过敏史请精简到" + MAX_ALLERGIES + "字以内");
        }
        if (historyText != null && historyText.length() > MAX_HISTORY) {
            throw new IllegalArgumentException("既往病史请精简到" + MAX_HISTORY + "字以内");
        }
        if (editorName != null && editorName.length() > MAX_EDITOR) editorName = editorName.substring(0, MAX_EDITOR);

        // 时间用 MemoParser 那个"演示统一的现在"（北京时间），不能用 LocalDateTime.now()：
        // 容器本身是 UTC，写 LocalDateTime.now() 的话，这张卡片上的"最近填写时间"会比
        // 同一屏里那条健康记录早 8 小时。备忘、记录、档案上的时间得是同一个钟。
        jdbc.update("""
                UPDATE users SET allergies=?,medical_history=?,height_cm=?,weight_kg=?,
                       health_profile_updated_by=?,health_profile_updated_at=?
                WHERE id=?
                """, allergyText, historyText, height, weight,
                editorName, Timestamp.valueOf(MemoParser.nowInDemoZone()), userId);
        return get(userId);
    }

    private static Integer parseHeight(String raw, Integer current) {
        String text = raw == null ? null : raw.trim();
        if (text == null) return current;
        if (text.isEmpty()) return null;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("身高请填数字，比如 158");
        }
        if (value < MIN_HEIGHT_CM || value > MAX_HEIGHT_CM) {
            throw new IllegalArgumentException("身高请在" + MIN_HEIGHT_CM + "到" + MAX_HEIGHT_CM + "厘米之间");
        }
        return value;
    }

    private static Double parseWeight(String raw, Double current) {
        String text = raw == null ? null : raw.trim();
        if (text == null) return current;
        if (text.isEmpty()) return null;
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("体重请填数字，比如 61.5");
        }
        if (value < MIN_WEIGHT_KG || value > MAX_WEIGHT_KG) {
            throw new IllegalArgumentException("体重请在" + (int) MIN_WEIGHT_KG + "到" + (int) MAX_WEIGHT_KG + "公斤之间");
        }
        return value;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * updatedBy 为空 = 这份档案从来没被人填过（data.sql 的种子数据拿它当"动过没有"的标记）。
     * updatedAt 有可能单独为空：种子数据不写时间（SQL 里算不出北京时间），页面上就只说
     * "最近由谁填写"、不报钟点；人在页面上改一次之后，时间就由 save 补上。
     */
    public record HealthProfile(String userId, Integer heightCm, Double weightKg,
                                String allergies, String medicalHistory,
                                String updatedBy, LocalDateTime updatedAt) { }
}
