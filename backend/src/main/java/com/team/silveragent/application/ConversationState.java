package com.team.silveragent.application;

import com.team.silveragent.agent.AgentRole;
import com.team.silveragent.domain.model.ToolModels.Conflict;
import com.team.silveragent.domain.model.ToolModels.Contact;
import com.team.silveragent.domain.model.ToolModels.Slot;
import com.team.silveragent.domain.model.ToolModels.TravelPlan;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

final class ConversationState {
    enum DialogueMode { GENERAL_CHAT, FOLLOWUP_FLOW, SUPPORT, SMALL_TALK }
    enum TaskStatus { NONE, ACTIVE, PAUSED, AWAITING_CONFIRMATION, COMPLETED, CANCELLED }

    /**
     * 会话本身的生命周期，与办理进度（{@link Stage}）正交：一段对话可以结束了
     * 而办理结果仍然留在历史里。只有 CLOSED 会拦下后续操作，EXPIRED 只是显示口径。
     */
    enum Status {
        /** 进行中 */
        ACTIVE,
        /** 用户主动点了「新对话」/「结束本次」——之后这个会话只读 */
        CLOSED,
        /** 太久没说话，历史里记为已结束；用户回来说话即恢复为进行中 */
        EXPIRED;

        /** 存储值可能是 NULL（改动之前的历史会话），一律按进行中处理。 */
        static Status fromStored(String value) {
            return value == null ? ACTIVE : valueOf(value);
        }
    }

    enum Stage {
        ASK_HOSPITAL, ASK_DEPARTMENT, ASK_DATE, ASK_ALTERNATIVE,
        ASK_COMPANION, ASK_TRAVEL, ASK_TRANSPORT, ASK_NOTIFY,
        READY_TO_PLAN, SELECT_PERIOD, CONFIRM_SLOT, SELECT_SLOT, NO_SLOT, CONFLICT,
        AWAITING_CONFIRMATION, COMPLETED, CANCELLED, EMERGENCY_PAUSED, PARTIAL, TOOL_ERROR,
        MEMO_TIME
    }

    final String id;
    /**
     * 本次会话服务的就诊人（老人端=本人；家属/志愿者端=被协同的长辈）。
     * 所有工具的 userId 都由它注入，界面上的预约、材料、路线也都属于这个人。
     */
    String userId;
    /** 谁在操作这个会话。老人端与 userId 相同；家属/志愿者端是那个照护者。 */
    String actorUserId;
    /** 操作者身份，由后端查 care_relations 判定；绝不来自模型或前端的角色字段。 */
    AgentRole actorRole = AgentRole.ELDER;
    /** 操作者与就诊人的关系称呼（女儿 / 社区志愿者），只用于话术与确认卡复述。 */
    String relationLabel;
    Stage stage = Stage.ASK_HOSPITAL;
    /** 会话生命周期状态。存在 conversation_sessions.status 列里，不进 state_json。 */
    Status status = Status.ACTIVE;
    DialogueMode dialogueMode = DialogueMode.GENERAL_CHAT;
    TaskStatus taskStatus = TaskStatus.NONE;
    String hospitalId;
    String hospital;
    String departmentId;
    String department;
    LocalDate date;
    Boolean acceptAlternative;
    Boolean needCompanion;
    Boolean needTravel;
    Boolean notifyFamily;
    String transport;
    Slot selectedSlot;
    Slot recommendedSlot;
    String timePreference;
    LocalTime requestedTime;
    List<Slot> alternatives = List.of();
    /** 最近一次日程检查查到的冲突。用户选择「仍保留这个时间」后，确认卡要把它列出来。 */
    List<Conflict> conflicts = List.of();
    TravelPlan travelPlan;
    Contact contact;
    List<String> materials = List.of();
    String pendingAction = "CREATE";
    String appointmentId;
    String pendingAppointmentId;
    /** 当前取消确认卡绑定的全部预约；单条取消也保存为一项，兼容旧快照时回退到 pendingAppointmentId。 */
    List<String> pendingAppointmentIds = List.of();
    Stage interruptedStage;
    String interruptedPendingAction;
    String interruptedPendingAppointmentId;
    String sideTask;
    String returnPolicy;
    /** 医院/科室口语只命中一个近似候选时，先保存候选并等待用户确认。 */
    String pendingEntityType;
    String pendingEntityId;
    String pendingEntityName;
    String pendingEntityRaw;
    /** 地图候选支线选中的方向：true=院内指引，false=院外路线。与取消支线互不共用。 */
    boolean travelInside;
    /** 待确认写操作的唯一钥匙。与下面两项同生共死：一起签、一起清（见 {@code ConfirmationService}）。 */
    String confirmationId;
    /**
     * 确认凭据（{@code confirmationId}）授权执行的是哪一类动作，取值为
     * {@code ConfirmationService.PendingOperation.Kind} 的名字。
     *
     * <p><b>签发时定下，之后不再变动</b>，并且随快照一起持久化。刻意存名字而不是枚举：
     * 快照里出现一个认不出来的名字时必须能发现（见 {@code Kind.stored}），
     * 存成枚举则反序列化直接抛异常，两处都不是「静默当成默认值」——那正是要避免的。
     *
     * <p>null＝这份凭据不可信（旧快照写的、或已被消费/废止）：一律当无效，要求重新确认。
     */
    String confirmationKind;
    /**
     * 这份凭据签发时锁定的那一批目标（目前只有取消族用得到），随快照持久化。
     *
     * <p>与 {@link #pendingAppointmentIds} 的分工：那个字段是「当前草稿想取消哪几条」，
     * 范围修订会改写它；这个是「这张卡已经向老人承诺过取消哪几条」，签发之后就冻住了。
     * 恢复时读的是这一份，所以重启不会把一批缩成一条。
     *
     * <p>null＝还原不出完整的目标集合（旧快照），一律作废凭据、要求重新确认，
     * <b>绝不按剩下的一部分执行</b>。空列表是另一个意思：这份凭据确实没有目标对象
     * （开新预约、备忘、代约取消都不针对已有预约号）。
     */
    List<String> confirmationTargetIds;
    String originalAppointmentId;
    /** 当前会话正在变更的“由家属/志愿者代约”的安排者 id；变更完成后用于回写通知，null=自己约的。 */
    String arrangedArrangerId;
    /** 隐式备忘在确认前的草稿（仅会话内存；确认后写 memos 表）。 */
    String pendingMemoText;
    /**
     * 草稿的到点时间。一句话说几天就是几条（“这周周一周二周三早八吃药”＝3 条），落库时逐条拆成备忘。
     *
     * <p>{@code null} 与空列表是<b>两件事</b>：空列表＝这条本来就是长期备忘（老人说了“不用提醒，
     * 只记下”）；{@code null} 只出现在更早版本写下的快照里——那时这里存的是单个时间，
     * 读回来是空。这跟健康记录草稿缺了记录时间是同一类问题：照着它执行只能把卡上那条提醒时间
     * 悄悄丢掉、记成一条长期备忘。<b>写进去的又一条他从来没在卡上看到过的东西。</b>
     * 所以 {@code ConfirmationService.payloadIntact} 把 {@code null} 判成凭据不可信，见那里。
     */
    java.util.List<java.time.LocalDateTime> pendingMemoAts;
    /** 草稿的重复规则（DAILY/WEEKLY/MONTHLY，null=只提醒一次）；追问钟点/日期时也要带着走。 */
    String pendingMemoRepeat;
    /** 老人刚回答清楚的“哪几天”（原话是“这周三”这类已过去的说法、或只说了“这周”时追问得来）；接下来只差钟点时带着它。 */
    java.util.List<java.time.LocalDate> pendingMemoDays;
    /** 助手侧正在改/删的那条已有备忘 id（“改第1条”点下来之后）；落地或取消后清空。仅会话内存。 */
    String pendingMemoId;
    /** 弹备忘确认卡前所在的办理阶段，确认后恢复，不打断复诊办理。 */
    Stage memoReturnStage;
    /** 暂存中的备忘是隐式（等钟点确认后再走确认卡）；false=显式（追问到钟点即可直写）。仅会话内存。 */
    boolean memoNeedsApproval;
    /** 会话停留在“家属/志愿者代约”管理开场（查看/改期/取消/求助）这一面，尚未转入本人新预约漏斗。
     * 仅会话内存：用于备忘记完后交回代约入口而不是反问医院；转入本人预约(askHospital)即清空。 */
    boolean managedMode;
    /**
     * 待记的那条实测数值（项目 / 数值 / 单位 / 原话 / 什么时候量的）。
     *
     * <p><b>不只是反问用的暂存。</b>它有两条路都会读到：反问“这个数不太对”时暂存这一条等老人表态，
     * 以及健康记录确认卡上写着的那条数值——后者和备忘草稿一样属于「签发那一刻定下的内容」，
     * 所以整套字段随快照一起持久化（见 {@code ConversationStore.Snapshot}），并由
     * {@code ConfirmationService.payloadIntact} 判「缺了就不执行」。旧快照里没有
     * {@link #pendingRecordAt}（它是这次才进快照的），读出来是 null，那张卡当场作废、请老人重新说一遍。
     *
     * <p>老人确认照记、改口重说或跑题后由 {@code clearPendingRecord} 一起清空。
     */
    String pendingRecordItem;
    java.math.BigDecimal pendingRecordValueNum;
    String pendingRecordValueText;
    String pendingRecordUnit;
    /** 老人报这条数值时的原话（“我的血压是100”）：落进记录里那一列，改了数据也还能看出他当时说的是什么。 */
    String pendingRecordRaw;
    /** 量到这个数的时间，<b>不是</b>点下确认的时间：卡上写着哪一刻，写进库的就必须是哪一刻。 */
    java.time.LocalDateTime pendingRecordAt;
    /**
     * 这一句里报的<b>其余几条</b>，按老人说的顺序排好，等着手上这条办完接着办
     * （“我的体温是36.5，心率80”里，36.5 那条之后的部分）。
     *
     * <p>确认卡上列着几行、写进库的就得是几条——这一列不在的话，重启之后那张写着两行的卡
     * 只会写下第一行，第二行在他点头之后凭空消失。所以它和上面那几样一样随快照持久化，
     * 并由 {@code ConfirmationService.payloadIntact} 判「缺了就不执行」。
     *
     * <p>{@code null} 与空列表是两件事：空列表＝这句话只有一条；{@code null} 只出现在
     * 更早版本写下的快照里，一律判成凭据不可信。新会话从空列表起步（见字段初值），
     * 而还原快照时是<b>原样赋值</b>——旧快照里没有这一列，读回来就是 null，不回填成空列表，
     * 否则那张写着两行的卡会照着"只有一行"执行。
     */
    java.util.List<PendingRecord> pendingRecordRest = java.util.List.of();

    /**
     * 待办的一条实测数值。确认卡上列着的那几行、以及反问之后等着写进库的那几条，都是它。
     *
     * <p>{@code issue} 存的是 {@code HealthRecordParser.Issue} 的名字：这一条还带着疑问
     * （量不出的数、没说单位的体重）时不能直接写，得照原样再问一次。存名字而不是重解析原话，
     * 是因为原话在这里已经拆开了，重新认一遍可能认成另一条。
     */
    record PendingRecord(String item, java.math.BigDecimal valueNum, String valueText, String unit,
                         String raw, java.time.LocalDateTime at, String issue) { }

    /** 反问/发卡前 pendingAction 的值，答完要还回去，不能把正在办的复诊流程打断。 */
    String recordReturnAction;
    /** 弹健康记录确认卡前所在的办理阶段，确认或拒绝后恢复；反问那条路不用（它不改阶段）。 */
    Stage recordReturnStage;
    boolean materialReminderDone;
    boolean departureReminderDone;
    boolean notificationDone;
    boolean scheduleChecked;

    ConversationState(String id, String userId) {
        this.id = id;
        this.userId = userId;
        this.actorUserId = userId;
        this.actorRole = AgentRole.ELDER;
    }

    /** 家属/志愿者会话：userId 是就诊人，actorUserId 是操作者。 */
    ConversationState(String id, String userId, String actorUserId, AgentRole actorRole, String relationLabel) {
        this(id, userId);
        if (actorUserId != null && !actorUserId.isBlank()) this.actorUserId = actorUserId;
        if (actorRole != null) this.actorRole = actorRole;
        this.relationLabel = relationLabel;
    }

    /** 是否为“代他人办理”的会话。 */
    boolean caregiving() {
        return actorRole != null && actorRole.isCaregiver() && !userId.equals(actorUserId);
    }
}
