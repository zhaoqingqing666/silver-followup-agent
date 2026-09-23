package com.team.silveragent;

import com.team.silveragent.application.health.HealthRecordParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一句话报了几项就记几条（“我的身高是180 体重是190”是两条，不是一条）。
 *
 * <p>原来一句话只落一条，而且数还会被隔壁的项目抢走：实测“我的身高是180 体重是190”
 * 记成了<b>体重 180</b>——“我体重190，血糖也高”记成了<b>血糖 190</b>，
 * 老人说的那件事一件没记对，还一声不响。
 */
class HealthRecordMultiValueTests {

    @Test
    void oneSentenceWithTwoItemsBecomesTwoRecords() {
        // 回归：这句话原来记成“体重 180 kg”——180 是身高那个数，被体重抢走了，
        // 老人报的两件事一件没记对。各认各的数，两条都留下
        List<HealthRecordParser.RecordIntent> records = HealthRecordParser.detectAll("我的身高是180 体重是190");

        assertThat(records).hasSize(2);
        assertThat(records.get(0).item()).isEqualTo("身高");
        assertThat(records.get(0).valueText()).isEqualTo("180");
        assertThat(records.get(0).unit()).isEqualTo("cm");
        assertThat(records.get(1).item()).isEqualTo("体重");
        assertThat(records.get(1).valueText()).isEqualTo("190");
    }

    @Test
    void eachItemTakesTheNumberWrittenAfterIt() {
        // 原来谁在项目表里排前面谁先被认走：心率排在体温前面，36.5 就记成了心率
        List<HealthRecordParser.RecordIntent> records = HealthRecordParser.detectAll("我的体温是36.5，心率80");

        assertThat(records).extracting(HealthRecordParser.RecordIntent::item).containsExactly("体温", "心率");
        assertThat(records.get(0).valueText()).isEqualTo("36.5");
        assertThat(records.get(1).valueText()).isEqualTo("80");
    }

    @Test
    void aPairAndASingleInOneSentenceBothSurvive() {
        // 回归：血压那一对读走后，后面的血糖整条被吞了
        List<HealthRecordParser.RecordIntent> records = HealthRecordParser.detectAll("血压120/80 血糖7.8");

        assertThat(records).extracting(HealthRecordParser.RecordIntent::item).containsExactly("血压", "血糖");
        assertThat(records.get(0).valueText()).isEqualTo("120/80");
        assertThat(records.get(1).valueText()).isEqualTo("7.8");
    }

    /**
     * 最糟的一种：项目认错，凭空造了一条老人没报过的记录。
     *
     * <p>实测原来记成“血糖 190 mmol/L”——190 是体重那个数，被排在词表前面的血糖抢走了。
     * 现在各认各的数：体重拿到 190（单位没交代，带 UNIT 出去问一句），血糖没数就不记。
     */
    @Test
    void aNumberIsNeverStolenByTheNeighbouringItem() {
        List<HealthRecordParser.RecordIntent> records = HealthRecordParser.detectAll("我体重190，血糖也高");

        assertThat(records).extracting(HealthRecordParser.RecordIntent::item).containsExactly("体重");
        assertThat(records.get(0).valueText()).isEqualTo("190");
        // 没有任何一条把 190 记到血糖头上
        assertThat(records).noneMatch(r -> "血糖".equals(r.item()));
    }

    @Test
    void oneItemIsStillExactlyOneRecord() {
        assertThat(HealthRecordParser.detectAll("我的血压是100")).hasSize(1);
        assertThat(HealthRecordParser.detectAll("我买了2斤苹果")).isEmpty();
        // 回查、撤销本来就只冲着一项走，不该拆出第二条
        assertThat(HealthRecordParser.detectAll("我最近血压多少")).hasSize(1);
        assertThat(HealthRecordParser.detectAll("记错了")).hasSize(1);
    }

    /** 门牌号、密码这类不是测量值，一项都不该记。 */
    @Test
    void nonMeasurementsAreNotRecords() {
        assertThat(HealthRecordParser.detectAll("帮我记一下我的锁屏密码是09")).isEmpty();
        assertThat(HealthRecordParser.detectAll("帮我记一下我住908房间")).isEmpty();
    }
}
