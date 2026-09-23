package com.team.silveragent;

import com.team.silveragent.application.memo.MemoParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 托付外壳的摘除：“帮我记下：我的手机密码是1111”要留下“我的手机密码是1111”。
 *
 * <p>这些是全量跑通之后实测出来的：外壳表少一个说法，那个说法就跟着正文一起进库，
 * 而这条正文是要念给家属听的。所以每条用例都钉住**整句相等**，不用 contains——
 * 出过的 bug 正是“正文里多留了半截”，contains 一个都抓不住。
 */
class MemoParserShellTests {

    /** 外壳摘干净之后剩下的应该是这句。 */
    private static void leaves(String said, String want) {
        MemoParser.MemoIntent memo = MemoParser.detect(said);
        assertThat(memo).as(said).isNotNull();
        assertThat(memo.text()).as(said).isEqualTo(want);
    }

    @Test
    void theLongestShellWins() {
        // 实测（老人随口一句“帮我记下：我的手机密码是1111”）：助手的回话是“已记下：‘我的手机密码是1111’”，
        // 可库里存的正文是“下：我的手机密码是1111”。外壳表里有“帮我记一下/帮我记下来”，独独没有
        // “帮我记下”，于是退到更短的“帮我记”去摘，剩下的“下：…”被当成了要记的内容。
        // 表里“帮我记下”和“帮我记”都匹配得上，长短由前缀长度决定，表怎么排都不会再错。
        String want = "我的手机密码是1111";
        leaves("帮我记下：我的手机密码是1111", want);
        leaves("帮我记下我的手机密码是1111", want);
        leaves("请帮我记下：我的手机密码是1111", want);
        leaves("帮我记下来：我的手机密码是1111", want);
    }

    @Test
    void aShellThatRunsIntoTheSentenceIsNotAShell() {
        // 反面：摘“记下”得看后面跟的是什么。“帮我记下午的药”后面接的是“午……”，摘了就成“午的药”，
        // 所以这种要退回去让短一点的“帮我记”来摘——摘完正是“下午的药”。
        leaves("帮我记下午的药", "下午的药");
        leaves("帮我记上午的药", "上午的药");
        // 真的只是命令壳、后面另起一句的，照旧按最长的那条摘干净
        leaves("帮我记下，下午的药在抽屉里", "下午的药在抽屉里");
    }

    @Test
    void courtesyAndLeadingWordsArePartOfTheShellToo() {
        // “麻烦”是同一件外壳的客气说法：“麻烦记一下，明天早上八点吃药”原来整句留在正文里
        leaves("麻烦帮我记下，明天早上八点吃药", "明天早上八点吃药");
        leaves("麻烦记一下，明天早上八点吃药", "明天早上八点吃药");
        // 「先记下这个，我再去办」里的“先”同理
        leaves("先帮我记下，明天早上八点吃药", "明天早上八点吃药");
    }

    @Test
    void theComplementHangingOffTheShellIsNotTheThing() {
        // “给我记**上**我今天走了5000步”：那个“上”是命令的一部分。原来只摘命令词本身，
        // 实测存成了“上我今天走了5000步”——半截话，而这条是要念给家属看的
        leaves("给我记上我今天走了5000步", "我今天走了5000步");
        // “记着点，…”摘完剩“点，吃药”；“给我记一下，…”剩“一下，吃药”
        leaves("记着点，明天早上八点吃药", "明天早上八点吃药");
        leaves("给我记一下，明天早上八点吃药", "明天早上八点吃药");
        leaves("帮我记着点，明天早上八点吃药", "明天早上八点吃药");
        leaves("帮我记住点，明天早上八点吃药", "明天早上八点吃药");
    }

    @Test
    void aComplementThatCanStartAWordIsLeftAlone() {
        // 单字补语要看着点：只有它后面正好断句才算补语，否则可能是下一个词的开头。
        // “记着点心怎么做”里的“点”属于“点心”，摘了就把事情说残了
        assertThat(MemoParser.detect("帮我记点心怎么做").text()).isEqualTo("点心怎么做");
    }

    @Test
    void strippingTheShellDoesNotChangeWhatAlreadyWorked() {
        // 不回归：原来就摘得对的那几种说法，结论必须一模一样
        leaves("提醒我明早八点吃药", "明早八点吃药");
        leaves("记一下，药盒放在电视柜第二层", "药盒放在电视柜第二层");
        leaves("帮我记着，我的医保卡在女儿那里", "我的医保卡在女儿那里");
        leaves("别忘了，我的复诊诊室是908", "我的复诊诊室是908");
    }
}
