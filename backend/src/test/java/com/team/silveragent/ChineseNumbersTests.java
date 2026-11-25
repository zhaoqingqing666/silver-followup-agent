package com.team.silveragent;

import com.team.silveragent.application.ChineseNumbers;
import com.team.silveragent.application.health.HealthRecordParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 口语里的中文数字：“一百五”要当 150 认，而“一三五”“三楼”不能被动。
 *
 * <p>这一条守住的是两个方向：漏认（老人读出来的数记不上，得换个说法再说一遍）
 * 和错认（把星期几、楼层当数值记进健康记录）。
 */
class ChineseNumbersTests {

    /* ---------- 该改的：带数量级或小数点的连读 ---------- */

    @Test
    void spokenNumberWithMagnitudeBecomesDigits() {
        assertThat(ChineseNumbers.normalize("血压一百五")).isEqualTo("血压150");
        assertThat(ChineseNumbers.normalize("两千三百")).isEqualTo("2300");
    }

    @Test
    void spokenDecimalBecomesDigits() {
        assertThat(ChineseNumbers.normalize("三十六点八")).isEqualTo("36.8");
    }

    /** 带“零”的按字面来：“一百零五”是 105，不是 150。 */
    @Test
    void spokenNumberWithZeroIsReadLiterally() {
        assertThat(ChineseNumbers.normalize("一百零五")).isEqualTo("105");
    }

    /* ---------- 不该改的：认不准就一个字都不动 ---------- */

    /** “一三五”是星期几，不是 135。 */
    @Test
    void weekdayRunIsLeftAlone() {
        assertThat(ChineseNumbers.normalize("每周一三五早上八点吃药")).isEqualTo("每周一三五早上八点吃药");
    }

    /** “三楼”是楼层，不是 3。 */
    @Test
    void floorWordIsLeftAlone() {
        assertThat(ChineseNumbers.normalize("我住三楼")).isEqualTo("我住三楼");
    }

    /** “七点半”的“点”后面不是数字，不算小数点。 */
    @Test
    void clockWordIsLeftAlone() {
        assertThat(ChineseNumbers.normalize("早上七点半量血压")).isEqualTo("早上七点半量血压");
    }

    /* ---------- 接进解析器之后：整句能认出数 ---------- */

    @Test
    void spokenValueIsParsedIntoARecord() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我的血压是一百三十八");

        assertThat(intent).isNotNull();
        assertThat(intent.item()).isEqualTo("血压");
        assertThat(intent.valueText()).isEqualTo("138");
        assertThat(intent.valueNum()).isEqualByComparingTo(BigDecimal.valueOf(138));
    }

    @Test
    void spokenDecimalIsParsedIntoARecord() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("体温三十六点八");

        assertThat(intent).isNotNull();
        assertThat(intent.item()).isEqualTo("体温");
        assertThat(intent.valueText()).isEqualTo("36.8");
    }

    /** “上压/下压”是老人更常说的说法，归到“血压”。 */
    @Test
    void spokenSystolicWordMapsToBloodPressure() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我上压一百五");

        assertThat(intent).isNotNull();
        assertThat(intent.item()).isEqualTo("血压");
        assertThat(intent.valueText()).isEqualTo("150");
    }

    /** 反过来：钟点不能被当成数值记进健康记录。 */
    @Test
    void clockNumberIsNotRecordedAsAValue() {
        assertThat(HealthRecordParser.detect("早上七点半量血压")).isNull();
    }
}
