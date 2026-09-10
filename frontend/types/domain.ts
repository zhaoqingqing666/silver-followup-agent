export type TabId = 'home' | 'tasks' | 'assistant' | 'profile';

/**
 * 首页点按钮进去的几个二级页，一页一件事：
 * reminders=有时间的备忘（提醒）、standing=没时间的备忘（长期备忘）、records=实测数值（健康记录）。
 * 不是底部 tab，所以不并进 TabId。
 */
export type RecordPage = 'reminders' | 'standing' | 'records';

export type PlanStepStatus = 'done' | 'active' | 'pending';

export interface PlanStep {
  id: string;
  title: string;
  detail: string;
  status: PlanStepStatus;
}

export interface AppointmentSummary {
  appointmentId: string;
  date: string;
  time: string;
  hospital: string;
  department: string;
  departureAt: string | null;
  transport: string | null;
  reminderStatus: string | null;
  familyStatus: string | null;
  materials: string[];
  requiredMaterials: string[];
  status: string;
  createdAt: string;
  /** 家属/志愿者代约时记录其账号 id；老人自己或助手约的为 null。 */
  arrangedBy: string | null;
  /** 显示用称呼，如“女儿 小丽”“社区志愿者 李阿姨”；非代约为 null。 */
  arrangedLabel: string | null;
  /** 陪同本次复诊的照护者账号 id；未确认陪同或老人自约为 null。照护端据此显示出发建议。 */
  accompaniedBy: string | null;
}

export interface MaterialItem {
  id: string;
  label: string;
  prepared: boolean;
  required: boolean;
}

export interface ChatMessage {
  id: string;
  role: 'assistant' | 'user';
  text: string;
}

export interface ToolTrace {
  toolName: string;
  parameters: string;
  result: string;
  success: boolean;
}

export interface AgentQuickReply {
  label: string;
  action: string;
  value: string;
}

export interface AgentPlanCard {
  hospital: string;
  department: string;
  date: string;
  selectedTime: string;
  tasks: string[];
  taskStatuses: string[];
  materials: string[];
  departureTime: string;
  familyContact: string;
}

export interface AgentConfirmationCard {
  confirmationId: string;
  title: string;
  operations: string[];
  impact: string;
  confirmText: string;
  cancelText: string;
}

export interface AgentResultCard {
  appointmentId: string;
  hospital: string;
  department: string;
  date: string;
  time: string;
  materials: string[];
  departureTime: string;
  reminderStatus: string;
  familyStatus: string;
}

export interface AgentTurnResponse {
  conversationId: string;
  stage: string;
  reply: string;
  quickReplies: AgentQuickReply[];
  plan: AgentPlanCard | null;
  confirmation: AgentConfirmationCard | null;
  result: AgentResultCard | null;
  toolTraces: ToolTrace[];
}

export interface ConversationHistoryResponse {
  conversationId: string;
  stage: string;
  messages: Array<{
    id: number;
    role: 'assistant' | 'user';
    content: string;
    createdAt: string;
  }>;
  current: AgentTurnResponse;
}

/** 老人端“健康备忘”：助手代记的健康/复诊相关小记。remindAt 为 null 表示长期备忘（无到点提醒）。 */
/** 重复提醒规则：DAILY 每天 / WEEKLY 每周 / MONTHLY 每月；null = 只提醒一次。 */
export type MemoRepeat = 'DAILY' | 'WEEKLY' | 'MONTHLY';

export interface HealthMemo {
  id: string;
  text: string;
  remindAt: string | null;
  repeatRule: MemoRepeat | null;
  status: string;
  createdAt: string;
}

/**
 * 老人端“健康记录”：助手帮老人记下的实测数值（血压/血糖/心率/体温/体重/血氧）。
 * 与 HealthMemo 的区别：备忘是“要做的事”（待办 + 到点提醒），健康记录是“已经量到的数”，
 * 只按测量时间倒序展示，不参与提醒。
 */
export interface HealthRecord {
  id: string;
  /** 项目：血压 / 血糖 / 心率 / 体温 / 体重 / 血氧。 */
  item: string;
  /** 数值文本，可能是数字（“100/60”），也可能是没给数值的说法（“有点高”）。 */
  valueText: string;
  /** 单位：mmHg / mmol/L / 次每分 / °C / kg / %。 */
  unit: string;
  /** 测量时间，不带时区的本地时间字符串，如 2026-09-10T11:20:00。 */
  recordedAt: string;
}

export interface UserProfile {
  id: string;
  name: string;
  homeAddress: string | null;
  preferredTransport: string | null;
  /** 展示用家属联系人；电话为后端脱敏掩码，不含明文。 */
  contacts?: FamilyContact[];
}

export interface FamilyContact {
  id: string;
  name: string;
  relationship: string;
  maskedPhone: string;
}

/** 登录身份：就诊人本人 / 家属 / 志愿者。 */
export type CareActor = 'ELDER' | 'FAMILY' | 'VOLUNTEER';

export type CareRole = 'FAMILY' | 'VOLUNTEER';

/** 协同长辈的最近预约快照。 */
export interface CareAppointmentSnapshot {
  appointmentId: string;
  /** 如 2026-09-20 */
  date: string;
  /** 如 15:00 */
  time: string;
  hospital: string;
  department: string;
  status: string;
  /** 建议出发时间 HH:mm；无则 null */
  departureAt: string | null;
  transport: string | null;
  familyStatus: string | null;
  /** 陪同这次复诊的照护者账号 id；老人自约或未确认陪同为 null。 */
  accompaniedBy: string | null;
}

/** 需要照护者关注的最近动态（老人请求协助 / 紧急暂停）；无则 null。 */
export interface CareAttention {
  title: string;
  tone: 'info' | 'success' | 'warning' | 'danger';
  detail: string | null;
  at: string;
}

export interface CareElder {
  elderId: string;
  name: string;
  relationship: string | null;
  role: CareRole;
  latestAppointment: CareAppointmentSnapshot | null;
  alert: string | null;
  attention: CareAttention | null;
}

export interface CareTimelineEvent {
  /** ISO 时间，如 2026-09-07T19:05:00 */
  at: string;
  tone: 'info' | 'success' | 'warning' | 'danger';
  title: string;
  detail?: string | null;
}

export interface CareNotification {
  notificationId: string;
  elderId: string;
  elderName: string;
  content: string;
  sentAt: string;
}

/** 帮助预约：可选医院（与老人端共用同一份科室目录）。 */
export interface BookingHospital {
  id: string;
  name: string;
  address: string;
  level: string;
  description: string;
  specialtyTags: string[];
  elderlyServices: string[];
}

/** 帮助预约：某医院下的科室。 */
export interface BookingDepartment {
  id: string;
  hospitalId: string;
  name: string;
  description: string;
  specialtyTags: string[];
  followupScope: string;
  location: string;
}

/** 帮助预约：某个日期下的可选号源。 */
export interface BookingSlotOption {
  slotId: string;
  /** 如 09:00 */
  time: string;
}

export interface BookingDateWindow {
  /** 如 2026-09-18 */
  date: string;
  slots: BookingSlotOption[];
}

/** 帮助预约 / 代约修改提交体。 */
export interface CreateBookingRequest {
  hospitalId: string;
  departmentId: string;
  date: string;
  slotId: string;
  needTravel: boolean;
  transport: string;
  /** 这次复诊当前照护者是否陪同就诊（陪同才在卡片显示出发建议）。 */
  willAccompany: boolean;
}

/** 代约成功返回的预约卡片（老人在首页/助手可见同款）。 */
export interface BookedAppointment {
  appointmentId: string;
  hospital: string;
  department: string;
  date: string;
  time: string;
  departureAt: string | null;
  transport: string;
  reminderStatus: string;
  familyStatus: string;
  materials: string[];
  status: string;
  accompaniedBy: string | null;
}
