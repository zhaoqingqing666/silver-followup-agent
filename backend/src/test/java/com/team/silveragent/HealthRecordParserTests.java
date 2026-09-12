package com.team.silveragent;

import com.team.silveragent.application.HealthRecordParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实测数值识别：把“我的血压是100”这类报告认成一条记录（而不是“要做的事”的备忘），
 * 同时不能把句子里顺带的钟点/日期数字当成数值（“8点量血压”不是血压=8）。
 */
class HealthRecordParserTests {

    @Test
    void bloodPressureValueIsRecorded() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我的血压是100");

        assertThat(intent).isNotNull();
        assertThat(intent.kind()).isEqualTo(HealthRecordParser.Kind.RECORD);
        assertThat(intent.item()).isEqualTo("血压");
        assertThat(intent.valueText()).isEqualTo("100");
        assertThat(intent.valueNum()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(intent.unit()).isEqualTo("mmHg");
    }

    @Test
    void systolicAndDiastolicPairIsKeptAsOneValue() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("刚才量的血压130/85");

        assertThat(intent).isNotNull();
        assertThat(intent.kind()).isEqualTo(HealthRecordParser.Kind.RECORD);
        assertThat(intent.item()).isEqualTo("血压");
        assertThat(intent.valueText()).isEqualTo("130/85");
    }

    @Test
    void aliasesAndUnitsMapToCanonicalItems() {
        assertThat(HealthRecordParser.detect("血糖7.2").item()).isEqualTo("血糖");
        assertThat(HealthRecordParser.detect("血糖7.2").unit()).isEqualTo("mmol/L");
        assertThat(HealthRecordParser.detect("心率72").item()).isEqualTo("心率");
        assertThat(HealthRecordParser.detect("体温36.8").item()).isEqualTo("体温");
        assertThat(HealthRecordParser.detect("体重65公斤").item()).isEqualTo("体重");
        assertThat(HealthRecordParser.detect("血氧98").item()).isEqualTo("血氧");
    }

    @Test
    void clockAndDateNumbersAreNotMistakenForValues() {
        // 回归：这句话里唯一的数字是钟点，不能记成“血压 8”
        assertThat(HealthRecordParser.detect("早上8点量血压")).isNull();
        assertThat(HealthRecordParser.detect("9月15号去测血糖")).isNull();
    }

    @Test
    void askingAboutAValueIsAQueryNotARecord() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我最近的血压是多少");

        assertThat(intent).isNotNull();
        assertThat(intent.kind()).isEqualTo(HealthRecordParser.Kind.QUERY);
        assertThat(intent.item()).isEqualTo("血压");

        // “我的血压怎么样”带“怎么”，不能被医疗建议话术吞掉
        assertThat(HealthRecordParser.detect("我的血压怎么样").kind())
                .isEqualTo(HealthRecordParser.Kind.QUERY);
    }

    @Test
    void plainQueryWithoutItemAsksForEverything() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我有哪些健康记录");

        assertThat(intent).isNotNull();
        assertThat(intent.kind()).isEqualTo(HealthRecordParser.Kind.QUERY);
        assertThat(intent.item()).isNull();
    }

    @Test
    void adviceWithoutANumberIsNotARecord() {
        assertThat(HealthRecordParser.detect("血压高怎么办")).isNull();
        assertThat(HealthRecordParser.detect("今天天气不错")).isNull();
    }

    @Test
    void absurdValueIsKeptForTheAssistantToQuestion() {
        // 回归：原来 800 被静默丢弃——老人报了个数，助手却接着问“去哪家医院”。
        // 现在照常带出来，只是打上“这个数不对”的标记，由助手反问是重测还是照记。
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我的血压是800");

        assertThat(intent).isNotNull();
        assertThat(intent.kind()).isEqualTo(HealthRecordParser.Kind.RECORD);
        assertThat(intent.needsConfirm()).isTrue();
        assertThat(intent.issue()).isEqualTo(HealthRecordParser.Issue.IMPOSSIBLE);
        assertThat(intent.valueText()).isEqualTo("800");
    }

    @Test
    void impossibleValuesAreJudgedPerItemNotByOneGlobalCeiling() {
        // 回归：原来所有项目共用一条上限 500，体温 60 度（人不可能）就大摇大摆记进去了
        assertThat(HealthRecordParser.detect("我的体温是60度").issue())
                .isEqualTo(HealthRecordParser.Issue.IMPOSSIBLE);
        assertThat(HealthRecordParser.detect("我血糖是100").issue())
                .isEqualTo(HealthRecordParser.Issue.IMPOSSIBLE);
        assertThat(HealthRecordParser.detect("血氧是150").issue())
                .isEqualTo(HealthRecordParser.Issue.IMPOSSIBLE);
        assertThat(HealthRecordParser.detect("我心率是400").issue())
                .isEqualTo(HealthRecordParser.Issue.IMPOSSIBLE);

        // 高得离谱但人真能量得出来的数不算“不可能”，照记不打扰老人
        assertThat(HealthRecordParser.detect("我的血压是200").needsConfirm()).isFalse();
        assertThat(HealthRecordParser.detect("体温39.5").needsConfirm()).isFalse();
        assertThat(HealthRecordParser.detect("血糖20").needsConfirm()).isFalse();
    }

    @Test
    void bothHalvesOfABloodPressurePairAreChecked() {
        // 回归：原来只看斜杠前面那个数，“血压 120/800”整条照记入库
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("刚才量的血压120/800");

        assertThat(intent.needsConfirm()).isTrue();
        assertThat(intent.issue()).isEqualTo(HealthRecordParser.Issue.IMPOSSIBLE);
        assertThat(intent.valueText()).isEqualTo("120/800");
    }

    @Test
    void bloodPressureInTheWrongOrderIsFlaggedAsSwapped() {
        HealthRecordParser.RecordIntent intent = HealthRecordParser.detect("我的血压是60/120");

        assertThat(intent.needsConfirm()).isTrue();
        assertThat(intent.issue()).isEqualTo(HealthRecordParser.Issue.SWAPPED);
    }

    @Test
    void normalPairIsNotFlagged() {
        assertThat(HealthRecordParser.detect("血压130/85").needsConfirm()).isFalse();
        assertThat(HealthRecordParser.detect("血压100/60").needsConfirm()).isFalse();
    }
}
