package com.team.silveragent.application;

import com.team.silveragent.domain.model.ToolModels.DepartmentProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 科室简称别名的解析结果，以及**别名没有放宽科室校验**这件事。
 *
 * <p>这个类不启 Spring：解析器是纯函数（吃一份目录候选、吐一个匹配结论），
 * 直接构造它、直接喂目录，比走一遍 {@code @SpringBootTest} 快得多，也更容易把
 * 「目录里有 / 没有 / 有两条」这三种情形分别摆出来。
 *
 * <p>为什么值得单独测：别名表是**唯一**一处「把老人口语映射到真实目录」的地方，
 * 而它旁边就是「非法科室要明确拒绝」这条硬要求。补别名时最容易犯的错，
 * 是顺手把匹配放宽（比如命中别名就直接返回，不看目录里到底有没有），
 * 那会让「呼吸科」在一个没有呼吸内科的医院里也被认下来。
 * 下面 {@code aliasNeverInventsADepartment...} 与 {@code ambiguous...} 两条就是钉这个的。
 */
class CatalogEntityResolverTests {

    private final CatalogEntityResolver resolver = new CatalogEntityResolver();

    // ------------------------------------------------------------------
    // 目录：市第一医院 h001 的 6 个科室，与 data.sql 的种子一致
    // （d001 心内 / d002 神内 / d005 内分泌 / d007 骨科 / d008 呼吸 / d009 消化）
    // ------------------------------------------------------------------

    private static DepartmentProfile department(String id, String name) {
        return new DepartmentProfile(id, "h001", name, name + "复诊", List.of(), "已由医生安排的复诊", "门诊楼");
    }

    /** DEC-026 之后的「每院各 6 科」科室名集合。 */
    private static List<DepartmentProfile> h001Departments() {
        return List.of(
                department("d001", "心内科"),
                department("d002", "神经内科"),
                department("d005", "内分泌科"),
                department("d007", "骨科"),
                department("d008", "呼吸内科"),
                department("d009", "消化内科"));
    }

    // ------------------------------------------------------------------
    // 本次补的两对别名
    // ------------------------------------------------------------------

    @Test
    void spokenShortNamesResolveToTheRealDepartmentAndStillAskForConfirmation() {
        // 「呼吸科」「消化科」两个方向都不是子串关系（"呼吸内科" 不含 "呼吸科"、
        // "呼吸科" 也不含 "呼吸内科"），包含匹配兜不住，所以只能靠别名表。
        assertResolvesTo("呼吸科", "呼吸内科");
        assertResolvesTo("消化科", "消化内科");
    }

    @Test
    void theShortNameAlsoResolvesInsideAWholeSentence() {
        // 老人口语不会只说三个字，往往说一整句。cleanUtterance 会先剥掉「我想去」「那就…吧」这类壳子，
        // 剥完剩下的就是简称，别名表再把它落到真实科室名上。
        assertThat(resolver.department("我想去呼吸科复诊", h001Departments()).type())
                .isEqualTo(CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE);
        assertThat(resolver.department("那就消化科吧", h001Departments()).only().name())
                .isEqualTo("消化内科");
    }

    // ------------------------------------------------------------------
    // 既有别名与既有路径：一个都没被这次改动碰坏
    // ------------------------------------------------------------------

    @Test
    void existingAliasesKeepWorking() {
        assertResolvesTo("心血管内科", "心内科");
        assertResolvesTo("心内", "心内科");
        assertResolvesTo("神内", "神经内科");
        assertResolvesTo("骨科门诊", "骨科");
    }

    /** 全称走的仍旧是精确匹配这条路（EXACT），不是近似匹配——别名表没有抢在前面。 */
    @Test
    void fullNamesStayExactMatches() {
        for (String name : List.of("心内科", "神经内科", "内分泌科", "骨科", "呼吸内科", "消化内科")) {
            CatalogEntityResolver.Match match = resolver.department(name, h001Departments());
            assertThat(match.type()).as("%s 应当精确匹配", name).isEqualTo(CatalogEntityResolver.MatchType.EXACT);
            assertThat(match.only().name()).isEqualTo(name);
        }
    }

    // ------------------------------------------------------------------
    // 「补别名没有放宽科室校验」——三条独立的证据
    // ------------------------------------------------------------------

    /**
     * 别名指向的科室在这家医院的目录里**不存在**时，仍然必须是 NOT_FOUND。
     *
     * <p>这是「别名编不出一个科室」的直接证据：呼吸科 → 呼吸内科这一跳之后，
     * 还是要去 rows 里找真的有没有「呼吸内科」。找不到就照旧说没有，
     * 不会因为「别名表里有这一条」就把一个不存在的科室认下来。
     */
    @Test
    void aliasNeverInventsADepartmentThatIsNotInTheCatalog() {
        // 构造一份**不含呼吸内科**的目录：别名表里明明写着「呼吸科 → 呼吸内科」，
        // 但这一跳之后仍要去目录里核对「呼吸内科」在不在。不在就该说没有。
        List<DepartmentProfile> withoutRespiratory = List.of(
                department("d001", "心内科"),
                department("d002", "神经内科"),
                department("d005", "内分泌科"),
                department("d007", "骨科"));

        CatalogEntityResolver.Match match = resolver.department("呼吸科", withoutRespiratory);
        assertThat(match.type())
                .as("目录里没有呼吸内科时，「呼吸科」不能被别名表认下来")
                .isEqualTo(CatalogEntityResolver.MatchType.NOT_FOUND);
        assertThat(match.candidates()).isEmpty();
    }

    /**
     * 同一个别名名下有**两条**候选时（把两家医院的目录一起喂进来），不许挑一条。
     *
     * <p>当前调用方（{@code FollowupAgentService.resolveDepartmentInput}）喂进来的永远是
     * <b>单家医院</b>的科室，所以两条同名只可能出现在「有人改了调用口径」的时候。
     * 这一条就是防那一天的护栏。
     *
     * <p>结论落在 {@code NOT_FOUND} 而不是 {@code AMBIGUOUS}，是因为别名那一支只在**唯一命中**时才给结论
     * （两条就落空），落空之后剩下的包含匹配也认不出它们——「呼吸内科」和「呼吸科」两个方向都不是子串。
     * 两条路都没替老人挑一家，这就是要钉住的东西；至于落到哪一档，只影响那句兜底话怎么写，
     * 不影响「绝不代选」。
     */
    @Test
    void aliasWithTwoCandidatesInTheListRefusesToPickOne() {
        List<DepartmentProfile> twoHospitals = List.of(
                new DepartmentProfile("d008", "h001", "呼吸内科", "呼吸内科复诊", List.of(), "随访", "门诊楼"),
                new DepartmentProfile("d011", "h002", "呼吸内科", "呼吸内科复诊", List.of(), "随访", "门诊楼"));

        CatalogEntityResolver.Match match = resolver.department("呼吸科", twoHospitals);
        assertThat(match.type())
                .as("同名科室有两条时不许替老人挑一家医院，只能给「不唯一」的结论")
                .isEqualTo(CatalogEntityResolver.MatchType.NOT_FOUND);
        assertThat(match.only()).as("两条候选时必须没有唯一结论").isNull();
    }

    /** 真正不存在的科室（目录里连近似的都没有）依旧明确拒绝，这是哪次改别名都不能破的底线。 */
    @Test
    void departmentsThatDoNotExistAreStillRefused() {
        for (String nonsense : List.of("眼科", "口腔科", "口腔", "皮肤科")) {
            CatalogEntityResolver.Match match = resolver.department(nonsense, h001Departments());
            assertThat(match.type())
                    .as("「%s」不是本项目的科室，必须拒绝而不是猜一个", nonsense)
                    .isEqualTo(CatalogEntityResolver.MatchType.NOT_FOUND);
            assertThat(match.candidates()).isEmpty();
        }
    }

    /** 多个真实候选时仍然只反问、不代选——「内科」同时是心内 / 神内 / 内分泌 / 呼吸 / 消化的后缀。 */
    @Test
    void ambiguousNamesAreStillExplainedInsteadOfGuessed() {
        CatalogEntityResolver.Match match = resolver.department("内科", h001Departments());
        assertThat(match.type()).isEqualTo(CatalogEntityResolver.MatchType.AMBIGUOUS);
        assertThat(match.only()).isNull();
        assertThat(match.candidates()).hasSizeGreaterThan(1);
    }

    /** 「不知道/随便」这类仍然走 UNCLEAR，不因为有了别名就被当成一次有效回答。 */
    @Test
    void unclearAnswersStayUnclear() {
        for (String vague : List.of("不清楚", "忘了", "随便")) {
            assertThat(resolver.department(vague, h001Departments()).type())
                    .as("「%s」应当仍然算没说清", vague)
                    .isEqualTo(CatalogEntityResolver.MatchType.UNCLEAR);
        }
    }

    private void assertResolvesTo(String spoken, String expectedName) {
        CatalogEntityResolver.Match match = resolver.department(spoken, h001Departments());
        assertThat(match.type()).as("「%s」应当是唯一近似候选", spoken)
                .isEqualTo(CatalogEntityResolver.MatchType.UNIQUE_APPROXIMATE);
        assertThat(match.only()).as("「%s」应当有且只有一条候选", spoken).isNotNull();
        assertThat(match.only().name()).isEqualTo(expectedName);
        // 表述：简称要被复述并让老人确认，确认前不写进办理状态（03-agent-workflow.md 第三节）。
        assertThat(match.raw()).isEqualTo(spoken);
    }
}
