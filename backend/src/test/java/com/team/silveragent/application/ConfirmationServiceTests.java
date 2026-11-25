package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.ConfirmationCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确认凭据与其授权范围的行为。
 *
 * <p>这里钉的是「写操作的唯一钥匙」那几条性质，它们都不是看代码能看出来的，而是要靠一连串
 * 调用才暴露：同一把钥匙只能用一次；用不了的请求<b>什么都不改</b>（否则老人按错一次就把自己
 * 那张还能用的卡弄没了）；<b>授权范围在签发时就冻住</b>（签完之后 {@code pendingAction} 被别处
 * 改过，也不能让同一把钥匙去执行另一件事）；以及<b>重启之后</b>那张批量取消卡取消的仍是原来
 * 那几条——缩水成一条，或者从一份读不出范围的旧快照里"凑合"执行一部分，都是这里要拦住的。
 */
class ConfirmationServiceTests {
    private final ConversationStore conversations = mock(ConversationStore.class);
    private final ConfirmationService confirmations = new ConfirmationService(conversations);

    private static final ConfirmationService.PendingOperation.Kind CANCEL =
            ConfirmationService.PendingOperation.Kind.CANCEL_APPOINTMENTS;
    private static final ConfirmationService.PendingOperation.Kind CANCEL_MANAGED =
            ConfirmationService.PendingOperation.Kind.CANCEL_MANAGED;

    private ConversationState conversation() {
        return new ConversationState("conversation", "user-001");
    }

    @Test
    void aCredentialCanBeConsumedExactlyOnce() {
        ConversationState state = conversation();
        String id = confirmations.issue(state, CANCEL, List.of("appt-001"));

        ConfirmationService.Decision first = confirmations.consume(state, id);

        assertThat(first.accepted()).isTrue();
        assertThat(first.operation().kind()).isEqualTo(CANCEL);
        // 消费即整份作废：凭据、类型、目标集合一起没。
        assertThat(state.confirmationId).isNull();
        assertThat(state.confirmationKind).isNull();
        assertThat(state.confirmationTargetIds).isNull();
        // 第二回：凭据已经作废，同一把钥匙用不了两次。
        assertThat(confirmations.consume(state, id).accepted()).isFalse();
    }

    /** 用不了的请求必须什么都不改：他还能按原来那个按钮重来一次。 */
    @Test
    void aRejectedRequestLeavesTheLiveCredentialAlone() {
        ConversationState state = conversation();
        // 备忘卡要写的那段正文在签发这一刻就得在（见 aMemoCardCannotBeIssuedWithoutItsText）。
        state.pendingMemoText = "明早八点提醒我吃药";
        // 到点时间也一样要在。"没有提醒"写成空列表、字段没设过是 null，两者不是一回事：
        // 卡上那一行提醒时间就是照着它写出来的（见 ConversationState.pendingMemoAts）。
        state.pendingMemoAts = List.of();
        String id = confirmations.issue(state, ConfirmationService.PendingOperation.Kind.MEMO, List.of());

        assertThat(confirmations.consume(state, "someone-elses-id").accepted()).isFalse();
        assertThat(confirmations.consume(state, null).accepted()).isFalse();

        assertThat(state.confirmationId).isEqualTo(id);
        assertThat(confirmations.consume(state, id).accepted()).isTrue();
    }

    /** 会话已经不在等待确认上时，手里那把钥匙一样不算数。 */
    @Test
    void aCredentialIsRejectedWhenTheSessionIsNoLongerWaiting() {
        ConversationState state = conversation();
        String id = confirmations.issue(state, CANCEL, List.of("appt-001"));
        state.stage = ConversationState.Stage.COMPLETED;

        assertThat(confirmations.consume(state, id).accepted()).isFalse();
        assertThat(state.confirmationId).isEqualTo(id);
    }

    /**
     * 目标集合是「签发时那份」，不跟着会话状态后来怎么变走。
     *
     * <p>若在确认时重新读一遍会话状态，卡片上写着的那几条和真正被取消的几条就有机会不是同一批，
     * 而老人是照着卡片点的头。
     */
    @Test
    void theTargetSetIsSnapshottedAtIssueTime() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentIds = List.of("appt-001", "appt-002");
        String id = confirmations.issue(state, CANCEL, state.pendingAppointmentIds);

        // 签发之后会话状态被别处改掉了（真实路径上是修订范围前的清理）。
        state.pendingAppointmentIds = List.of("appt-009");

        ConfirmationService.Decision decision = confirmations.consume(state, id);

        assertThat(decision.operation().cancellationTargets())
                .containsExactly("appt-001", "appt-002");
    }

    /**
     * <b>动作类型在签发时定死，不在确认时按 {@code pendingAction} 现算。</b>
     *
     * <p>这是一条真实会发生的错配：卡是取消卡，签发之后中间某一步（改期、旁支、以后新加的
     * 分支）把 {@code pendingAction} 写成了别的值。若确认时反过来拿这个可变字段重算类型，
     * 同一把钥匙就会去执行另一件事——老人点头的是取消，执行的是开单。
     */
    @Test
    void aCredentialExecutesTheActionItWasIssuedForEvenIfThePendingActionChanges() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        String id = confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));

        // 签发之后业务被改道。
        state.pendingAction = "MEMO";

        ConfirmationService.Decision decision = confirmations.consume(state, id);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.operation().kind()).isEqualTo(CANCEL);
        assertThat(decision.operation().cancellationTargets()).containsExactly("appt-001", "appt-002");
    }

    /**
     * 认不出的类型名一律不认，<b>绝不退成默认值</b>。
     *
     * <p>以前的写法是「认不出来就当 {@code BOOKING}」，而 {@code BOOKING} 恰好是全类里唯一
     * 会真去开一条新预约的类型：一份被写坏或被回滚的快照里的陌生类型名，会静默变成一次开单。
     * 所以这里连"默认"都不给它留。
     */
    @Test
    void anUnknownKindIsNeverTreatedAsTheDefault() {
        assertThat(ConfirmationService.PendingOperation.Kind.stored("NOT_A_KIND")).isNull();
        assertThat(ConfirmationService.PendingOperation.Kind.stored("")).isNull();
        assertThat(ConfirmationService.PendingOperation.Kind.stored(null)).isNull();
        // 认得出的一定要认得出：四个类型名都要能原样往返。
        for (ConfirmationService.PendingOperation.Kind kind : ConfirmationService.PendingOperation.Kind.values()) {
            assertThat(ConfirmationService.PendingOperation.Kind.stored(kind.name())).isEqualTo(kind);
        }
    }

    /** 类型名认不出来时：凭据当场作废，而且一件事都不执行。 */
    @Test
    void aCredentialWithAnUnreadableKindExecutesNothing() {
        ConversationState state = conversation();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = "credential";
        state.confirmationKind = "SOMETHING_FROM_ANOTHER_VERSION";
        state.confirmationTargetIds = List.of("appt-001");

        ConfirmationService.Decision decision = confirmations.consume(state, "credential");

        assertThat(decision.accepted()).isFalse();
        assertThat(state.confirmationId).isNull();
        assertThat(state.confirmationKind).isNull();
        assertThat(state.confirmationTargetIds).isNull();
    }

    /**
     * 空目标集合与"读不出来的目标集合"是两回事。
     *
     * <p>空是"这份凭据本来就不针对某个已有对象"（开新预约、备忘、代约取消），
     * null 才是"还原不出来"。前者照常执行，后者一律作废——把两者混在一起，
     * 要么会把备忘卡也误判成失效，要么会给旧快照里那张批量卡开一条"按剩下来的执行"的路。
     */
    @Test
    void anEmptyTargetSetIsLegitimateWhileAnUnknownOneIsNot() {
        ConversationState booking = conversation();
        String id = confirmations.issue(booking, ConfirmationService.PendingOperation.Kind.BOOKING, List.of());

        ConfirmationService.Decision decision = confirmations.consume(booking, id);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.operation().targetIds()).isEmpty();
    }

    /**
     * 旧快照：只有凭据，没有类型和目标集合（两者是这次才进快照的）。
     *
     * <p>这份凭据还原不出"当初授权了什么"，所以整份作废、请他重新确认——
     * <b>绝不按 {@code pendingAppointmentId} 那一条凑合执行</b>：卡片上可能写着三条，
     * 只取消一条比什么都不做严重得多，而老人根本看不出少了哪两条。
     */
    @Test
    void anOldSnapshotWithoutAnAuthorizedScopeIsDroppedInsteadOfPartlyExecuted() {
        ConversationState reloaded = conversation();
        reloaded.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        reloaded.confirmationId = "credential-from-before-this-change";
        reloaded.pendingAction = "CANCEL_EXISTING";
        reloaded.pendingAppointmentId = "appt-001";
        reloaded.pendingAppointmentIds = List.of("appt-001", "appt-002");
        reloaded.appointmentId = "appt-007";

        ConfirmationService.Decision decision = confirmations.consume(reloaded, "credential-from-before-this-change");

        assertThat(decision.accepted()).isFalse();
        // 而且当场作废：留着它，老人会一直按一个永远不会执行的按钮，还不知道要重说一遍。
        assertThat(reloaded.confirmationId).isNull();
        assertThat(reloaded.confirmationKind).isNull();
        assertThat(reloaded.confirmationTargetIds).isNull();
    }

    /**
     * 会话恢复：凭据、类型、完整目标集合一起从快照回来，一条都不少。
     *
     * <p>这里把"进快照的那几个字段"逐字搬进一个新对象来模拟重启（真实那条路存库再读回，
     * 由 {@code ConversationRecoveryTests} 走库验证）。只要目标集合没进快照，这个搬运就会丢，
     * 所以这条测试同时也是"它必须被持久化"的护栏。
     */
    @Test
    void theFullTargetSetSurvivesASessionReload() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentIds = List.of("appt-001", "appt-002", "appt-003");
        confirmations.issue(state, CANCEL, state.pendingAppointmentIds);

        ConfirmationService.Decision decision = confirmations.consume(fromSnapshotOf(state), state.confirmationId);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.operation().kind()).isEqualTo(CANCEL);
        assertThat(decision.operation().cancellationTargets())
                .containsExactly("appt-001", "appt-002", "appt-003");
    }

    /** 上面那条的反面：搬运时丢掉授权范围，就不许执行——防止"恢复"这件事被写成只带凭据。 */
    @Test
    void aCredentialWhoseScopeDidNotSurviveTheReloadIsNotUsable() {
        ConversationState state = conversation();
        confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));
        ConversationState brokenReload = fromSnapshotOf(state);
        brokenReload.confirmationTargetIds = null;

        assertThat(confirmations.consume(brokenReload, state.confirmationId).accepted()).isFalse();
    }

    /** 状态里的那份卡如果读不出授权范围，就不能当成"还有一张卡在等"再摆回去。 */
    @Test
    void reconcileWillNotReattachACardWhoseScopeIsUnreadable() {
        ConversationState state = conversation();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = "credential";
        when(conversations.lastResponse("conversation"))
                .thenReturn(Optional.of(responseWithCard("credential")));

        AgentTurnResponse reconciled = confirmations.reconcile(state, plainResponse());

        assertThat(reconciled.confirmation()).isNull();
        assertThat(state.confirmationId).isNull();
    }

    /** 重新签发不会继承上一份的授权范围。 */
    @Test
    void aReissuedCredentialDoesNotInheritTheOldScope() {
        ConversationState state = conversation();
        String first = confirmations.issue(state, ConfirmationService.PendingOperation.Kind.BOOKING, List.of());

        confirmations.clear(state);
        String second = confirmations.issue(state, CANCEL, List.of("appt-new"));

        assertThat(second).isNotEqualTo(first);
        assertThat(confirmations.consume(state, first).accepted()).isFalse();
        ConfirmationService.Decision decision = confirmations.consume(state, second);
        assertThat(decision.operation().kind()).isEqualTo(CANCEL);
        assertThat(decision.operation().cancellationTargets()).containsExactly("appt-new");
    }

    /** 补发只在手上真的有一份可信凭据时保留它：已有那份必须原样返回，否则老人屏幕上的按钮会突然失效。 */
    @Test
    void ensureIssuedKeepsTheCredentialAlreadyHandedOut() {
        ConversationState state = conversation();
        String id = confirmations.issue(state, ConfirmationService.PendingOperation.Kind.BOOKING, List.of());

        assertThat(confirmations.ensureIssued(state, ConfirmationService.PendingOperation.Kind.BOOKING, List.of()))
                .isEqualTo(id);
        assertThat(confirmations.ensureIssued(state, ConfirmationService.PendingOperation.Kind.BOOKING, List.of()))
                .isEqualTo(id);

        state.confirmationId = null;
        assertThat(confirmations.ensureIssued(state, ConfirmationService.PendingOperation.Kind.BOOKING, List.of()))
                .isNotEqualTo(id);
    }

    /**
     * <b>类型不同就不许复用。</b>
     *
     * <p>这是这套逻辑里最坏的一种组合：屏幕上是一张新卡（开新预约），钥匙却是上一件事
     * （批量取消）的。老人对着新卡点头，执行出来的是旧事——而屏幕上没有任何东西提示他这一点。
     * 所以补发只看"手上有没有一份内容相同的授权"，不看"手上有没有凭据"。
     */
    @Test
    void ensureIssuedDoesNotReuseACredentialOfADifferentKind() {
        ConversationState state = conversation();
        String cancel = confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));

        String booking = confirmations.ensureIssued(state,
                ConfirmationService.PendingOperation.Kind.BOOKING, List.of());

        assertThat(booking).isNotEqualTo(cancel);
        assertThat(state.confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.BOOKING.name());
        assertThat(state.confirmationTargetIds).isEmpty();
        // 旧凭据连同它的类型与目标集合一起作废：拿着它消费不了任何东西。
        assertThat(confirmations.consume(state, cancel).accepted()).isFalse();
    }

    /** 同一类型但目标集合不一样，同样是另一份授权：换钥匙，旧的那把当场失效。 */
    @Test
    void ensureIssuedDoesNotReuseACredentialWithADifferentTargetSet() {
        ConversationState state = conversation();
        String twoTargets = confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));

        String oneTarget = confirmations.ensureIssued(state, CANCEL, List.of("appt-001"));

        assertThat(oneTarget).isNotEqualTo(twoTargets);
        assertThat(state.confirmationTargetIds).containsExactly("appt-001");
        assertThat(confirmations.consume(state, twoTargets).accepted()).isFalse();
        // 新凭据带着新范围，照常能用——换钥匙不是把这件事一起废掉。
        ConfirmationService.Decision decision = confirmations.consume(state, oneTarget);
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.operation().cancellationTargets()).containsExactly("appt-001");
    }

    /**
     * 完全相同才复用：类型一样、目标集合逐条一样，返回的就是原来那个 {@code confirmationId}。
     *
     * <p>写法上来一点差别（重复一条）不算另一份授权——{@code [a, a, b]} 和 {@code [a, b]}
     * 要取消的是同一批预约，为这个换钥匙只会让老人屏幕上那个按钮平白失效一次。
     */
    @Test
    void ensureIssuedReusesOnlyAnIdenticalAuthorization() {
        ConversationState state = conversation();
        String id = confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));

        assertThat(confirmations.ensureIssued(state, CANCEL, List.of("appt-001", "appt-002"))).isEqualTo(id);
        // 顺序也一样才叫相同：接口上「先取消哪条」是调用方给的信息。
        assertThat(confirmations.ensureIssued(state, CANCEL, List.of("appt-002", "appt-001"))).isNotEqualTo(id);

        // 上面那一次已经把钥匙换掉了，重新拿一份干净的重来一遍。
        ConversationState again = conversation();
        String stable = confirmations.issue(again, CANCEL, List.of("appt-001", "appt-002"));
        assertThat(confirmations.ensureIssued(again, CANCEL, List.of("appt-001", "appt-001", "appt-002")))
                .isEqualTo(stable);
        assertThat(confirmations.consume(again, stable).accepted()).isTrue();
    }

    /**
     * 补发的参数校验与签发同一条规矩：{@code kind} 与 {@code targetIds} 都不能为 {@code null}。
     *
     * <p>{@code targetIds} 尤其不能通融：{@code null} 在这套字段里的含义已经定死是
     * "旧快照缺字段 / 不可信"，签发侧再把它当成空列表，就等于用一个只有这里才会做的转换，
     * 盖住调用方少写的那件事——而这两者存进快照之后<b>读起来一模一样</b>，等他重启一回，
     * 谁也说不清当初是"本来就没有目标"还是"目标丢了"。
     */
    @Test
    void issuingOrEnsuringWithoutAKindOrATargetSetIsAProgrammingError() {
        assertThatThrownBy(() -> confirmations.issue(conversation(), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> confirmations.issue(conversation(), CANCEL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> confirmations.ensureIssued(conversation(), null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> confirmations.ensureIssued(conversation(), CANCEL, null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 被拒的那次补发不能留下半份现场：没通过校验就不该动原来的授权。 */
    @Test
    void aRejectedEnsureIssuedLeavesTheLiveCredentialAlone() {
        ConversationState state = conversation();
        String id = confirmations.issue(state, CANCEL, List.of("appt-001"));
        state.pendingAction = "CANCEL_EXISTING";

        assertThatThrownBy(() -> confirmations.ensureIssued(state, CANCEL, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(state.confirmationId).isEqualTo(id);
        assertThat(state.confirmationKind).isEqualTo(CANCEL.name());
        assertThat(state.confirmationTargetIds).containsExactly("appt-001");
        assertThat(confirmations.consume(state, id).accepted()).isTrue();
    }

    /**
     * 目标集合的条数在<b>签发</b>这一刻就卡住，不留到执行时。
     *
     * <p>这是「卡上写 A、执行 B」那条边界的第一道：照护端那张取消卡只处理单条，签发时给两条
     * 就是一次说不清的授权；给零条则等于回到"执行时再去找一条当前预约"的老路上去。
     */
    @Test
    void theTargetCountIsEnforcedAtIssueTime() {
        assertThatThrownBy(() -> confirmations.issue(conversation(), CANCEL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirmations.issue(conversation(), CANCEL_MANAGED, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirmations.issue(conversation(), CANCEL_MANAGED, List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        // 不针对已有对象的那些类型反过来：给目标就是错的。
        assertThatThrownBy(() -> confirmations.issue(conversation(),
                ConfirmationService.PendingOperation.Kind.BOOKING, List.of("appt-001")))
                .isInstanceOf(IllegalArgumentException.class);

        // 补发走同一条校验，不能被"手上正好有一份能复用的"绕过。
        ConversationState state = conversation();
        confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));
        assertThatThrownBy(() -> confirmations.ensureIssued(state, CANCEL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(state.confirmationTargetIds).containsExactly("appt-001", "appt-002");
    }

    /** 忘了草稿就不许发备忘卡——它上面写着的那段正文是执行时唯一的内容来源。 */
    @Test
    void aMemoCardCannotBeIssuedWithoutItsText() {
        ConversationState state = conversation();
        assertThatThrownBy(() -> confirmations.issue(state, ConfirmationService.PendingOperation.Kind.MEMO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        // 炸了就不能留下半份现场：状态里不该出现一个背后什么都没有的凭据。
        assertThat(state.confirmationId).isNull();
        assertThat(state.confirmationKind).isNull();
    }

    /** 读不出来的那份同样要换掉，否则它会一路带到确认那一步才作废。 */
    @Test
    void ensureIssuedReplacesACredentialItCannotRead() {
        ConversationState state = conversation();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        state.confirmationId = "credential-from-an-old-snapshot";

        String issued = confirmations.ensureIssued(state,
                ConfirmationService.PendingOperation.Kind.BOOKING, List.of());

        assertThat(issued).isNotEqualTo("credential-from-an-old-snapshot");
        assertThat(state.confirmationKind)
                .isEqualTo(ConfirmationService.PendingOperation.Kind.BOOKING.name());
    }

    /**
     * 三种结束方式都必须清掉<b>整份</b>授权，只清凭据是最容易犯、又最看不出来的那种错。
     *
     * <p>只清 {@code confirmationId} 时，所有判据都会说"没有待确认的东西"，可状态里还躺着一个
     * 类型和一个目标集合；等下一次签发把它们覆盖掉，中间任何一条按"状态里还剩什么"读代码的路
     * 都会读到一个没有凭据背书的范围。
     */
    @Test
    void everyWayOfEndingACredentialClearsTheWholeScope() {
        record Ending(String name, java.util.function.BiConsumer<ConfirmationService, ConversationState> run) { }
        List<Ending> endings = List.of(
                new Ending("consume", (service, state) -> service.consume(state, state.confirmationId)),
                new Ending("clear", ConfirmationService::clear),
                new Ending("retire", ConfirmationService::retire));

        for (Ending ending : endings) {
            ConversationState state = conversation();
            confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));

            ending.run().accept(confirmations, state);

            assertThat(state.confirmationId).as(ending.name()).isNull();
            assertThat(state.confirmationKind).as(ending.name()).isNull();
            assertThat(state.confirmationTargetIds).as(ending.name()).isNull();
        }
    }

    /** 消费这一条单独再走一遍：它必须先把操作交出来，再清整份授权。 */
    @Test
    void consumingHandsOutTheOperationBeforeClearingTheWholeScope() {
        ConversationState state = conversation();
        String id = confirmations.issue(state, CANCEL, List.of("appt-001", "appt-002"));

        ConfirmationService.Decision decision = confirmations.consume(state, id);

        assertThat(decision.operation().cancellationTargets()).containsExactly("appt-001", "appt-002");
        assertThat(state.confirmationId).isNull();
        assertThat(state.confirmationKind).isNull();
        assertThat(state.confirmationTargetIds).isNull();
    }

    /**
     * 退卡必须同时退出「等待确认」，并且回到被打断的那一步接着办。
     *
     * <p>凭据没了却还停在等待确认上，屏幕就一直等一个再也不会亮的按钮。
     */
    @Test
    void retiringACardLeavesAwaitingConfirmationAndGoesBackToTheInterruptedStep() {
        ConversationState state = conversation();
        state.pendingAction = "CANCEL_EXISTING";
        state.pendingAppointmentIds = List.of("appt-001");
        state.interruptedStage = ConversationState.Stage.ASK_DATE;
        confirmations.issue(state, CANCEL, state.pendingAppointmentIds);

        confirmations.retire(state);

        assertThat(state.confirmationId).isNull();
        assertThat(state.confirmationKind).isNull();
        assertThat(state.confirmationTargetIds).isNull();
        assertThat(state.pendingAppointmentIds).isEmpty();
        assertThat(state.pendingAction).isEqualTo("CREATE");
        assertThat(state.stage).isEqualTo(ConversationState.Stage.ASK_DATE);
        assertThat(state.taskStatus).isEqualTo(ConversationState.TaskStatus.ACTIVE);
    }

    /** 没有被打断过就落到已完成；两条路都不能停在等待确认上。 */
    @Test
    void retiringWithoutAnInterruptedStepSettlesAsCompleted() {
        ConversationState state = conversation();
        confirmations.issue(state, ConfirmationService.PendingOperation.Kind.BOOKING, List.of());

        confirmations.retire(state);

        assertThat(state.stage).isEqualTo(ConversationState.Stage.COMPLETED);
        assertThat(state.taskStatus).isEqualTo(ConversationState.TaskStatus.COMPLETED);
    }

    /** 单纯清凭据不改阶段：那些出口的业务状态自己会走到别处，多改一脚就是另一个 bug。 */
    @Test
    void clearingDropsOnlyTheCredential() {
        ConversationState state = conversation();
        state.pendingAction = "MEMO";
        state.pendingMemoText = "明早八点提醒我吃药";
        // 与上面那张卡同一个道理：到点时间（空列表＝长期备忘）也是签发时要有的草稿。
        state.pendingMemoAts = List.of();
        confirmations.issue(state, ConfirmationService.PendingOperation.Kind.MEMO, List.of());

        confirmations.clear(state);

        assertThat(state.confirmationId).isNull();
        assertThat(state.stage).isEqualTo(ConversationState.Stage.AWAITING_CONFIRMATION);
        assertThat(state.pendingAction).isEqualTo("MEMO");
    }

    /**
     * 出站校正的两个方向，都以「手上到底有没有一份能读出来的授权」为准，不以 stage 为准：
     * 卡还在就原样带回（老人正要按的按钮不能因为他多问一句就消失），凭据没了就退出等待确认。
     */
    @Test
    void reconciliationReattachesTheCardTheCredentialStillHolds() {
        ConversationState state = conversation();
        state.confirmationId = "credential";
        state.confirmationKind = ConfirmationService.PendingOperation.Kind.BOOKING.name();
        state.confirmationTargetIds = List.of();
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        when(conversations.lastResponse("conversation"))
                .thenReturn(Optional.of(responseWithCard("credential")));
        AgentTurnResponse answer = plainResponse();

        AgentTurnResponse reconciled = confirmations.reconcile(state, answer);

        assertThat(reconciled.confirmation()).isNotNull();
        assertThat(reconciled.confirmation().confirmationId()).isEqualTo("credential");
        assertThat(reconciled.reply()).isEqualTo(answer.reply());
        assertThat(state.stage).isEqualTo(ConversationState.Stage.AWAITING_CONFIRMATION);
    }

    /** 上一轮那张卡不是这份凭据签的（比如范围刚被改过），就不能当它还活着。 */
    @Test
    void reconciliationRetiresTheCardWhenTheCredentialIsGone() {
        ConversationState state = conversation();
        state.confirmationId = null;
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        when(conversations.lastResponse("conversation"))
                .thenReturn(Optional.of(responseWithCard("an-older-credential")));

        AgentTurnResponse reconciled = confirmations.reconcile(state, plainResponse());

        assertThat(reconciled.confirmation()).isNull();
        assertThat(reconciled.stage()).isEqualTo("COMPLETED");
        assertThat(state.stage).isEqualTo(ConversationState.Stage.COMPLETED);
    }

    /** 不在等待确认上就原样放行，一个字都不改。 */
    @Test
    void reconciliationLeavesOtherStagesUntouched() {
        ConversationState state = conversation();
        state.stage = ConversationState.Stage.ASK_DATE;
        AgentTurnResponse answer = plainResponse();

        assertThat(confirmations.reconcile(state, answer)).isSameAs(answer);
    }

    /**
     * 模拟重启：只搬进快照的那几个字段，别的都不带（会话内存里那些草稿字段本来就该丢）。
     * 目标集合若没进快照，这里就搬不过去——所以这个搬运同时也是它在快照里的护栏。
     */
    private static ConversationState fromSnapshotOf(ConversationState state) {
        ConversationState reloaded = new ConversationState(state.id, state.userId);
        reloaded.stage = state.stage;
        reloaded.confirmationId = state.confirmationId;
        reloaded.confirmationKind = state.confirmationKind;
        reloaded.confirmationTargetIds = state.confirmationTargetIds;
        reloaded.pendingAction = state.pendingAction;
        return reloaded;
    }

    private AgentTurnResponse responseWithCard(String confirmationId) {
        return new AgentTurnResponse("conversation", "AWAITING_CONFIRMATION", "请确认", List.of(),
                null, new ConfirmationCard("请确认复诊办理计划", List.of("医院科室：市第一医院 · 心内科"),
                "确认后按以上内容执行。", "确认办理", "返回修改", confirmationId),
                null, List.of(), null, "请确认", null, null);
    }

    private AgentTurnResponse plainResponse() {
        return new AgentTurnResponse("conversation", "AWAITING_CONFIRMATION", "阿司匹林是…", List.of(),
                null, null, null, List.of(), null, "阿司匹林是…", null, null);
    }
}
