package com.team.silveragent.application;

import com.team.silveragent.domain.model.AgentTurnResponse;
import com.team.silveragent.domain.model.AgentTurnResponse.ConfirmationCard;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 确认凭据的唯一出处：签发、校验、消费、废止、对账，全部只在这里发生。
 *
 * <p>凭据（{@code confirmationId}）是全部写操作的唯一钥匙，所以它必须只有一处判罚：
 * 谁拿得到、什么时候失效、一次能用几回、<b>授权的到底是哪件事、对哪几条对象</b>。
 * 散在业务代码里各写一遍赋值，「什么时候该作废」就会有好几种口径，而其中最危险的一种是
 * <b>凭据还在、授权范围却变了</b>——下一轮拿到同一个会话的人以为手上还举着一件原来那件事。
 *
 * <p>因此这一份授权由三样东西<b>一起</b>组成，全部存在 {@link ConversationState} 上、
 * 全部随 {@code ConversationStore} 的 {@code Snapshot} 持久化，任何一处缺失都当凭据不存在：
 * <ul>
 *   <li>{@link ConversationState#confirmationId}：这份凭据本身。</li>
 *   <li>{@link ConversationState#confirmationKind}：授权执行哪一类动作。<b>签发时定下</b>，
 *       绝不在确认时再看 {@code pendingAction} 现算——那个字段是各业务分支随手改的，
 *       拿它当依据，「凭据没换、业务被改道」的组合就能执行出另一件事。</li>
 *   <li>{@link ConversationState#confirmationTargetIds}：签发时锁定的<b>完整</b>目标集合。
 *       恢复时读的是这一份，所以重启之后那张批量取消卡取消的仍是原来那几条，不会缩水。</li>
 * </ul>
 * 三样同生共死：{@link #issue} 一起写，{@link #consume}、{@link #clear}、{@link #retire}
 * 一起清。只清其中一两样，就是留一份「凭据没了、授权范围还在」的残骸给下一轮用。
 *
 * <p><b>认不出来的一律 fail closed。</b>旧快照里只有 {@code confirmationId}，没有另外两样
 * （它们是这次才进快照的）；也可能出现一个认不出的类型名。这两种情况都还原不出「当初授权了
 * 什么」，所以整份凭据当场作废、请老人重新确认一次——<b>绝不退化成「按剩下来的那部分执行」</b>。
 * 代价是重启后旧快照里那张卡一定作废（哪怕是开新预约那种本来就没有目标的卡，因为看不出它
 * 是哪一类，就无法断定它是「本来没有目标」还是「目标丢了」）。宁可让他再说一遍，也不要按一个
 * 我们推出来的范围写库。
 *
 * <p>同一把尺子也量「这一类动作执行时还要用到的草稿」：备忘卡要写的那条草稿不在授权三样里，
 * 它也得随快照一起回来（见 {@link #payloadIntact}）。草稿缺了，凭据就不可信了——照着一份
 * 残缺的草稿写库，写进去的是一条他从来没看到过的备忘。
 *
 * <p>本类不执行任何业务动作（那是 {@link CancellationExecutor} 一类执行器的事），
 * 也不决定「执行不了时改说什么」（那是各业务出口的事）。
 */
@Component
final class ConfirmationService {
    /**
     * 一张卡确认下来要执行的那件事。
     *
     * @param confirmationId 这份操作是绑在哪一次签发上的
     * @param kind           哪一类业务动作，决定谁来执行。<b>签发时由调用方给死</b>
     *                       （四处签发各自知道自己在建哪张卡），存进快照、随凭据一起恢复，
     *                       中途不重算。这一条挡住了「凭据没换，{@code pendingAction} 被别处
     *                       改过，于是同一把钥匙执行了另一件事」。
     * @param targetIds      签发那一刻定下的完整目标集合（目前只有取消族用得到）。存「签发时那份」
     *                       而不是「确认时再读一遍会话状态」：卡上写的和执行的要的是同一批，
     *                       中间不能换过，重启也不能缩水。
     */
    record PendingOperation(String confirmationId, Kind kind, List<String> targetIds) {
        /**
         * 一张卡确认下来要执行的那件事，以及<b>由谁的业务链路执行</b>。
         *
         * <p>"本人自办"与"替他人代办"是<b>两个类型</b>，不是同一个类型加上执行时再看一眼
         * {@code caregiving()}：那一眼用的又是可变状态，等于把「谁来执行」这件已经冻在凭据里的事
         * 重新交还给会话。备选做法（把执行上下文塞进授权、类型仍合并）也能用，但那样分派器就要
         * 同时看类型和一个附加上下文；拆成两个取值，判据仍然只有一个。
         */
        enum Kind {
            /** 本人照草稿开新预约 / 改期。 */
            BOOKING,
            /** 家属/志愿者替长辈照草稿代办 / 改期（走 {@code CareBookingService}）。 */
            BOOKING_CAREGIVER,
            /** 本人取消已确认预约，可能是一批。 */
            CANCEL_APPOINTMENTS,
            /** 家属/志愿者取消长辈那一条进行中的预约（业务只允许单条）。 */
            CANCEL_APPOINTMENTS_CAREGIVER,
            /** 取消"由家属或志愿者代约"的那份安排（本人端的「临时改期或取消」）。 */
            CANCEL_MANAGED,
            /** 写一条健康备忘。 */
            MEMO;

            /**
             * 从快照里存下来的名字还原。<b>认不出来返回 {@code null}，不设默认值。</b>
             *
             * <p>这里以前会在认不出来时一律当 {@code BOOKING}——那是全类里最危险的一个默认值，
             * 因为 {@code BOOKING} 是唯一一个"会真去开一条新预约"的类型：一个被写坏/被回滚
             * 得快照里的陌生类型名，会静默变成一次开单。宁可不认它，让凭据作废、请老人重新确认。
             *
             * <p>也不再用 {@code pendingAction} 现算类型（旧写法 {@code of(pendingAction)}）：
             * 那个字段可变，而凭据授权的范围必须不变。
             */
            static Kind stored(String name) {
                if (name == null) return null;
                for (Kind kind : values()) {
                    if (kind.name().equals(name)) return kind;
                }
                return null;
            }

            /** 这一类动作是不是「取消某一批已有预约」。 */
            boolean cancelsAppointments() {
                return this == CANCEL_APPOINTMENTS || this == CANCEL_APPOINTMENTS_CAREGIVER;
            }

            /**
             * 这一类动作的业务是不是<b>只认一条</b>目标。
             *
             * <p>代他人办理的取消走的是「这位长辈当前那一条预约」这条业务，本身只处理单条上。
             * 要求恰好一条、而不是"取第一条"，是为了让卡上写的那条和真正取消的那条不可能不是
             * 同一条——多出来的目标在这里就是一个必须炸出来的错，不能默默丢掉。
             */
            boolean singleTargetOnly() {
                return this == CANCEL_MANAGED || this == CANCEL_APPOINTMENTS_CAREGIVER;
            }
        }

        /** 取消这批预约。空列表交给执行器报错，和它自己判空时是同一个说法。 */
        List<String> cancellationTargets() {
            return targetIds == null ? List.of() : targetIds;
        }

        /**
         * 这张卡锁定的<b>那一条</b>目标；不是恰好一条就返回 {@code null}，由调用方给出提示。
         *
         * <p>刻意<b>不</b>写成 {@code targetIds.get(0)}：静默取第一条正是「卡上写 A、实际取消 B」
         * 这类错的开端。执行器拿到 {@code null} 一个写操作都不做。
         */
        String singleTarget() {
            List<String> targets = cancellationTargets();
            return targets.size() == 1 ? targets.get(0) : null;
        }
    }

    /**
     * 一次确认请求的裁决。
     *
     * @param operation 通过校验的那件事；没通过就是 {@code null}
     */
    record Decision(PendingOperation operation) {
        boolean accepted() {
            return operation != null;
        }
    }

    private final ConversationStore conversations;

    ConfirmationService(ConversationStore conversations) {
        this.conversations = conversations;
    }

    /**
     * 签发一份新凭据，把"授权执行哪件事、对哪几条对象"一起写进会话状态。
     *
     * <p>签发即作废前一份：同一个会话手上只该有一张卡。这里会顺手把 {@code stage} 置成等待确认
     * （存量代码里签发与建卡是同一件事），但<b>不改任务状态</b>——四张卡里有两张会把任务标成
     * 等待确认、两张不会，这个差别属于各自的业务语义，由调用方决定。
     *
     * @param kind      这次授权的是哪一类动作。传 {@code null} 是调用点的编程错误，直接炸，
     *                  不能让一份"不知道授权了什么"的凭据生出来——它进了快照就是永久性的
     * @param targetIds 这次授权的完整目标集合；无目标的卡<b>显式传空列表</b>。
     *                  <b>不接受 {@code null}</b>：{@code null} 在这套字段里的含义已经定死是
     *                  "旧快照缺字段 / 不可信"，在这里悄悄把它当成空列表，就等于用一个只有签发侧
     *                  才会做出的转换，去遮盖调用方少写了一件事——而这两者读起来一模一样
     * @throws IllegalArgumentException 目标集合的条数与这一类业务对不上（取消类必须有目标，
     *                                  只认单条的那两类必须恰好一条，无目标的那三类必须为空）。
     *                                  这是调用点的编程错误，宁可在这里炸，也不让一张
     *                                  "写着一件事、执行起来是另一件"的卡发出去
     */
    String issue(ConversationState state, PendingOperation.Kind kind, List<String> targetIds) {
        Objects.requireNonNull(kind, "签发凭据必须给出动作类型");
        Objects.requireNonNull(targetIds, "签发凭据必须显式给出目标集合；没有目标请传 List.of()，不要传 null");
        List<String> targets = normalized(targetIds);
        requireTargetCount(kind, targets);
        // 这一类动作执行时还要用到的草稿，签发这一刻就得在。备忘卡尤其：它上面写着的那段正文
        // 不在凭据三样里，缺了它这张卡要么写不出东西，要么只能拿会话里别的什么凑一段——
        // 两种结果都是"老人对着 A 点头、写进去的是 B"。所以在发卡这一步就拦住。
        if (!payloadIntact(state, kind)) {
            throw new IllegalArgumentException(kind + " 的确认卡缺少执行时需要的草稿，"
                    + "请先把内容补全再签发");
        }
        String id = UUID.randomUUID().toString();
        state.confirmationId = id;
        state.confirmationKind = kind.name();
        state.confirmationTargetIds = targets;
        state.stage = ConversationState.Stage.AWAITING_CONFIRMATION;
        return id;
    }

    /**
     * 目标集合的条数必须和这一类业务对得上。
     *
     * <p>这一步是<b>签发侧</b>的护栏，与执行侧那道（{@link PendingOperation#singleTarget()}）
     * 成对：只写一侧，另一侧换个入口就能绕过去。以前代约取消这张卡正是签发时传了空列表、
     * 执行时又去现找"当前那份安排"——两个口径各说各话，卡上写的那条和真正取消的那条就可能
     * 不是同一条。
     */
    private static void requireTargetCount(PendingOperation.Kind kind, List<String> targets) {
        // 三条是互斥的，按「这类动作要不要求目标」分：恰好一条 / 至少一条 / 一条都不要。
        // 代约取消（CANCEL_MANAGED）属于第一类而不是第三类——它不发整批，但要锁定的那一条
        // 比谁都不能少；把它漏进第三类，正是「卡上不写目标、执行时现找」那个老毛病换了个写法。
        if (kind.singleTargetOnly()) {
            if (targets.size() != 1) {
                throw new IllegalArgumentException(kind + " 的确认卡必须恰好锁定一条目标预约，实际 "
                        + targets.size() + " 条");
            }
            return;
        }
        if (kind.cancelsAppointments()) {
            if (targets.isEmpty()) {
                throw new IllegalArgumentException(kind + " 的确认卡至少要锁定一条目标预约，"
                        + "没有目标请改签不发目标的类型");
            }
            return;
        }
        if (!targets.isEmpty()) {
            throw new IllegalArgumentException(kind + " 的确认卡不针对已有预约，目标集合必须为空，实际 "
                    + targets.size() + " 条");
        }
    }

    /**
     * 手上没有<b>内容相同</b>的可信凭据时补发一份，有就原样返回。
     *
     * <p>给"重述待确认内容"这类场景用（老人说"打开地图"、"再说一遍要带什么"）：卡片内容要
     * 原样再说一次，凭据也必须还是原来那份，否则屏幕上那个按钮看着没动、其实已经换了钥匙。
     *
     * <p>复用的条件是<b>整份授权逐项相同</b>：类型一样，目标集合也一样。只比"有没有凭据"是不够的——
     * 同一把钥匙配着一份<b>别的</b>授权，正好是最坏的一种组合：屏幕上是一张新卡（比如开新预约），
     * 钥匙却是上一件事（比如批量取消）的，老人对着新卡点确认，执行出来的是旧事。所以类型或目标
     * 有任何一处不同，都当"手上没有可复用的授权"处理：{@link #issue} 一份新的，
     * 旧凭据连同它的类型与目标集合一起作废。
     *
     * <p>"有"的判据是 {@link #restored} 能把它读出来，不是 {@code confirmationId} 不为空：
     * 旧快照里那份读不出来的凭据在这里也得换掉，否则它会一路带到确认那一步才作废。
     *
     * @throws NullPointerException {@code kind} 或 {@code targetIds} 为 {@code null}，
     *                             与 {@link #issue} 同一条规矩
     */
    String ensureIssued(ConversationState state, PendingOperation.Kind kind, List<String> targetIds) {
        Objects.requireNonNull(kind, "补发凭据必须给出动作类型");
        Objects.requireNonNull(targetIds, "补发凭据必须显式给出目标集合；没有目标请传 List.of()，不要传 null");
        List<String> targets = normalized(targetIds);
        // 条数校验在复用之前：手上那份就算读得出来，"这次要签的"本身不合法也不能顺手复用。
        requireTargetCount(kind, targets);
        PendingOperation existing = restored(state);
        if (existing != null && sameAuthorization(existing, kind, targets)) {
            return existing.confirmationId();
        }
        return issue(state, kind, targetIds);
    }

    /** 同一份授权＝同一个类型＋同一批目标（都取规范形态后逐条相同）。 */
    private static boolean sameAuthorization(PendingOperation existing, PendingOperation.Kind kind,
                                             List<String> targetIds) {
        return existing.kind() == kind && existing.targetIds().equals(targetIds);
    }

    /**
     * 目标集合的规范形态：去重、保留先后顺序。
     *
     * <p>比较和存取都过这一道，是为了让"同一批目标"有一个唯一写法：{@code [a, a, b]} 与
     * {@code [a, b]} 指的是同一批要取消的预约，不该因为它们写法不同就换一把钥匙（那样屏幕上
     * 那张卡上的按钮会平白失效一次）。顺序保留——不排序，接口上「先取消哪条」是调用方给的信息。
     */
    private static List<String> normalized(List<String> targetIds) {
        return List.copyOf(new LinkedHashSet<>(targetIds));
    }

    /**
     * 校验并消费凭据，把待确认的那件事交出去。
     *
     * <p>不通过时<b>什么都不动</b>：会话状态与凭据都保持原样，所以他还能按原来那个按钮重来一次。
     * 通过则当场作废整份授权（凭据、类型、目标集合一起清），随后即使执行失败也不会再认第二回。
     *
     * <p>还原不出授权范围时（旧快照缺类型/缺目标集合、或类型名认不出来）也算"不通过"，
     * 但<b>照样当场作废</b>：那份凭据已经不可信了，留着它只会让老人反复按一个永远不会执行的按钮，
     * 而他不知道要重说一遍。<b>绝不用剩下的一部分目标凑合执行</b>——卡片上写着三条、实际取消一条，
     * 是比"什么都没发生"严重得多的结果。
     */
    Decision consume(ConversationState state, String confirmationId) {
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) return new Decision(null);
        if (confirmationId == null || !confirmationId.equals(state.confirmationId)) return new Decision(null);
        PendingOperation operation = restored(state);
        dropCredential(state);
        return new Decision(operation);
    }

    /**
     * 把状态里那份凭据读出来；任何一处对不上就当它不存在。
     *
     * <p>三样必须齐全：凭据本身、认得出的类型、以及（哪怕是空的）目标集合。
     * 缺任何一样都返回 {@code null}，由调用方决定"作废并请老人重新确认"。
     *
     * <p>再加一道 {@link #payloadIntact}：这一类动作执行时还要用到的草稿也必须还在。
     */
    private PendingOperation restored(ConversationState state) {
        if (state.confirmationId == null) return null;
        PendingOperation.Kind kind = PendingOperation.Kind.stored(state.confirmationKind);
        if (kind == null) return null;
        if (state.confirmationTargetIds == null) return null;
        if (!payloadIntact(state, kind)) return null;
        return new PendingOperation(state.confirmationId, kind, normalized(state.confirmationTargetIds));
    }

    /**
     * 这一类动作执行时还要用到的东西，是不是也完整地在这儿。
     *
     * <p>授权三样（凭据 / 类型 / 目标集合）说的是"哪件事、对哪几条对象"，但并不总是"这件事的
     * 全部内容"——备忘要写的那段正文与提醒时间不在其中，它另有草稿字段。那些字段同样是
     * <b>签发那一刻定下的</b>，所以和授权三样一样随快照回来，也一样"缺了就整份作废"。
     *
     * <p>旧快照正好缺这一段（备忘草稿以前根本不进快照）。要在那种快照上执行，只能拿一个
     * {@code null} 正文去写库，或者按会话里别的什么凑一段出来——两条路都指向同一件事：
     * <b>写进去一条他从来没在卡上看到过的备忘</b>。所以这里返回 {@code false}，
     * 凭据当场作废、请他重新说一遍。
     */
    private static boolean payloadIntact(ConversationState state, PendingOperation.Kind kind) {
        if (kind == PendingOperation.Kind.MEMO) {
            return state.pendingMemoText != null && !state.pendingMemoText.isBlank();
        }
        return true;
    }

    /**
     * 让手上这张还没执行的卡作废，并<b>退出"等待确认"</b>。
     *
     * <p>退卡和退状态必须是同一件事：凭据没了却还停在等待确认上，屏幕就会一直等一个再也不肯
     * 亮起来的按钮——老人按哪儿都不对，也说不清自己卡在哪一步。回退目标优先是他被打断前的节点
     * （办理还在进行中），没有就落到已完成。
     */
    void retire(ConversationState state) {
        dropCredential(state);
        state.pendingAppointmentId = null;
        state.pendingAppointmentIds = List.of();
        state.pendingAction = "CREATE";
        if (state.interruptedStage != null) {
            state.stage = state.interruptedStage;
            state.taskStatus = ConversationState.TaskStatus.ACTIVE;
        } else {
            state.stage = ConversationState.Stage.COMPLETED;
            state.taskStatus = ConversationState.TaskStatus.COMPLETED;
        }
    }

    /**
     * 只是把这份授权丢掉，别的什么都不动。
     *
     * <p>给那些「业务状态自己会走到别处、凭据顺带失效」的出口用。走 {@link #retire} 会连阶段
     * 一起改写，在那些地方是多余的一脚。
     */
    void clear(ConversationState state) {
        dropCredential(state);
    }

    /**
     * 清掉整份授权：凭据、类型、目标集合。<b>这三样只能一起清</b>。
     *
     * <p>只清凭据是最容易犯的一种错，而且看不出来：{@code confirmationId} 为空，所有判据都
     * 说"没有待确认的东西"，可状态里还躺着一个类型和一个目标集合。等下一次签发把它们覆盖掉，
     * 中间任何一条按"状态里还剩什么"读代码的路（对账、快照、以后新加的出口）都会读到一个
     * 没有凭据背书的范围。
     */
    private void dropCredential(ConversationState state) {
        state.confirmationId = null;
        state.confirmationKind = null;
        state.confirmationTargetIds = null;
    }

    /**
     * 手上那张卡如果还有效，原样拿出来（同一个 {@code confirmationId}）。
     *
     * <p>取的是上一轮真正发给老人的那张（{@code lastResponse}），不是按当前状态重新拼一张——
     * 卡片内容必须只有一处来源。重新拼一份，就等于把「他看到的」和「会执行的」分成两样东西：
     * 内容一旦不一致，他会以为自己确认的是屏幕上那份。
     */
    ConfirmationCard stillValidCard(ConversationState state) {
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) return null;
        PendingOperation operation = restored(state);
        if (operation == null) return null;
        return conversations.lastResponse(state.id)
                .map(AgentTurnResponse::confirmation)
                .filter(card -> card != null && operation.confirmationId().equals(card.confirmationId()))
                .orElse(null);
    }

    /**
     * 出站前的最后一道一致性校正：说「有卡在等确认」，这一轮就必须真的带着卡。
     *
     * <p>两个方向各修一种错，都以「手上到底有没有一份能读出来的授权」为准，不以 {@code stage} 为准：
     * <ul>
     *   <li><b>非修订的追问</b>（问一句药、问一句流程）时卡还在，就原样带回——老人正要按的
     *       「确认办理」不能因为他多问了一句话就消失。DEC-016 给越界提示块修的是这件事，
     *       这里把它变成一条对每一轮都成立的约束，而不是逐个出口去补。</li>
     *   <li><b>卡已经作废</b>（范围被改、卡被执行、或从一份读不出授权范围的旧快照恢复）时
     *       退出等待确认。凭据为空却仍报「等待确认」，前端就得等着一个不存在的按钮；
     *       他在这一刻能做的任何事，都成了猜。</li>
     * </ul>
     */
    AgentTurnResponse reconcile(ConversationState state, AgentTurnResponse response) {
        if (state.stage != ConversationState.Stage.AWAITING_CONFIRMATION) return response;
        if (response.confirmation() != null) return response;
        ConfirmationCard pending = stillValidCard(state);
        if (pending != null) return withStageAndCard(response, response.stage(), pending);
        retire(state);
        return withStageAndCard(response, state.stage.name(), null);
    }

    /** 只换 stage 与 confirmation，其余字段原样——校正的是这两者，不是内容。 */
    private AgentTurnResponse withStageAndCard(AgentTurnResponse response, String stage,
                                               ConfirmationCard card) {
        return new AgentTurnResponse(response.conversationId(), stage, response.reply(),
                response.quickReplies(), response.plan(), card, response.result(), response.toolTraces(),
                response.task(), response.speechText(), response.uiDirective(), response.notice());
    }
}
