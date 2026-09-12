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
    String confirmationId;
    String originalAppointmentId;
    /** 当前会话正在变更的“由家属/志愿者代约”的安排者 id；变更完成后用于回写通知，null=自己约的。 */
    String arrangedArrangerId;
    /** 隐式备忘在确认前的草稿（仅会话内存；确认后写 memos 表）。 */
    String pendingMemoText;
    java.time.LocalDateTime pendingMemoAt;
    /** 草稿的重复规则（DAILY/WEEKLY/MONTHLY，null=只提醒一次）；追问钟点/日期时也要带着走。 */
    String pendingMemoRepeat;
    /** 老人刚回答清楚的“哪一天”（原话是“这周三”这类已过去的说法时追问得来）；接下来只差钟点时带着它。 */
    java.time.LocalDate pendingMemoDay;
    /** 助手侧正在改/删的那条已有备忘 id（“改第1条”点下来之后）；落地或取消后清空。仅会话内存。 */
    String pendingMemoId;
    /** 弹备忘确认卡前所在的办理阶段，确认后恢复，不打断复诊办理。 */
    Stage memoReturnStage;
    /** 暂存中的备忘是隐式（等钟点确认后再走确认卡）；false=显式（追问到钟点即可直写）。仅会话内存。 */
    boolean memoNeedsApproval;
    /** 会话停留在“家属/志愿者代约”管理开场（查看/改期/取消/求助）这一面，尚未转入本人新预约漏斗。
     * 仅会话内存：用于备忘记完后交回代约入口而不是反问医院；转入本人预约(askHospital)即清空。 */
    boolean managedMode;
    /** 反问“这个数不太对”时暂存的待记数值（仅会话内存）；老人确认照记、改口重说或跑题后清空。 */
    String pendingRecordItem;
    java.math.BigDecimal pendingRecordValueNum;
    String pendingRecordValueText;
    String pendingRecordUnit;
    /** 反问前 pendingAction 的值，答完要还回去，不能把正在办的复诊流程打断。 */
    String recordReturnAction;
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
