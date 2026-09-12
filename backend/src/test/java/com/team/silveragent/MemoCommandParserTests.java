package com.team.silveragent;

import com.team.silveragent.application.memo.MemoCommandParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手侧“查/改/删已有备忘”的意图识别。重点两条：
 * 一是要认“改第2条”这种助手自己教给老人的说法，二是不能把改复诊预约的话抢过来。
 */
class MemoCommandParserTests {

    @Test
    void listPhrasesAreRecognized() {
        assertThat(MemoCommandParser.detect("我都有哪些备忘").kind()).isEqualTo(MemoCommandParser.Kind.LIST);
        assertThat(MemoCommandParser.detect("看看我的提醒").kind()).isEqualTo(MemoCommandParser.Kind.LIST);
    }

    @Test
    void editAndDeleteWithoutMemoWordAreRecognizedByOrdinal() {
        // 回归：清单底下提示老人“要改哪条就说改第几条”，这种说法里没有“备忘/提醒”两个字
        MemoCommandParser.MemoCommand edit = MemoCommandParser.detect("改第2条");
        assertThat(edit.kind()).isEqualTo(MemoCommandParser.Kind.UPDATE);
        assertThat(edit.head()).contains("第2条");
        assertThat(edit.tail()).isNull();

        MemoCommandParser.MemoCommand delete = MemoCommandParser.detect("删第2条");
        assertThat(delete.kind()).isEqualTo(MemoCommandParser.Kind.DELETE);

        assertThat(MemoCommandParser.detect("删掉第三条").kind()).isEqualTo(MemoCommandParser.Kind.DELETE);
    }

    @Test
    void newTimeInTheSameSentenceIsSplitOut() {
        MemoCommandParser.MemoCommand command = MemoCommandParser.detect("把第2条改到明天早上八点");

        assertThat(command.kind()).isEqualTo(MemoCommandParser.Kind.UPDATE);
        assertThat(command.head()).isEqualTo("把第2条");
        assertThat(command.tail()).isEqualTo("明天早上八点");

        // 不带“改到”连写也要能切开（“改第2条到明天早上八点”）
        MemoCommandParser.MemoCommand loose = MemoCommandParser.detect("改第2条到明天早上八点");
        assertThat(loose.tail()).isEqualTo("明天早上八点");
        assertThat(loose.head()).contains("第2条");
    }

    @Test
    void memoWordPhrasesStillSplitOnChangeWord() {
        MemoCommandParser.MemoCommand command = MemoCommandParser.detect("把吃药那条提醒改到明天早上八点");

        assertThat(command.kind()).isEqualTo(MemoCommandParser.Kind.UPDATE);
        assertThat(command.head()).isEqualTo("把吃药那条提醒");
        assertThat(command.tail()).isEqualTo("明天早上八点");
    }

    @Test
    void appointmentTalkIsNotHijacked() {
        // 没有“备忘/提醒”，也没有清单序号：很可能是在改复诊预约，不能当成改备忘
        assertThat(MemoCommandParser.detect("改成下周三")).isNull();
        assertThat(MemoCommandParser.detect("改成每周三下午三点")).isNull();
        assertThat(MemoCommandParser.detect("取消预约")).isNull();
        assertThat(MemoCommandParser.detect("我想去市一医院")).isNull();
    }
}
