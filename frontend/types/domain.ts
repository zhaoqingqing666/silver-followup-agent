export type TabId = 'home' | 'tasks' | 'assistant' | 'profile' | 'travel';

/**
 * 首页点按钮进去的几个二级页，一页一件事：
 * reminders=有时间的备忘（提醒）、standing=没时间的备忘（长期备忘）、records=实测数值（健康记录）。
 * 不是底部 tab，所以不并进 TabId。
 */
export type RecordPage = 'reminders' | 'standing' | 'records';

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
  appointmentId: string;
  materialCode: string;
  materialName: string;
  required: boolean;
  status: 'NOT_PREPARED' | 'PREPARED' | 'PHOTO_CONFIRMED';
  confirmSource: string | null;
  photoUrl: string | null;
  updatedAt: string;
}

export interface VoicePreference {
  userId: string;
  autoSpeakEnabled: boolean;
  speechRate: number;
  speechVolume: number;
}

export interface ChatMessage {
  id: string;
  role: 'assistant' | 'user';
  text: string;
  /** 这条消息随附的图片（data URL）。只在本次会话的内存里，刷新后不再有。 */
  imageDataUrls?: string[];
  /** 这条消息是按住说话发出来的 */
  isVoice?: boolean;
  /** MediaRecorder 录下的原话，点语音条回放；同样是内存态，刷新后失效 */
  audioUrl?: string;
  audioDuration?: number;
  /** 语音消息的识别状态：识别中 / 已出文字 / 识别失败（录音仍可回放） */
  voiceState?: 'transcribing' | 'done' | 'error';
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

/** 后端封闭枚举；前端遇到未知 type 必须安全忽略，不跳空白页。 */
export type AgentUiDirectiveType =
  | 'NONE' | 'OPEN_ASSISTANT' | 'OPEN_TASKS' | 'OPEN_MATERIALS' | 'OPEN_TRAVEL'
  | 'SHOW_OUTSIDE_ROUTE' | 'SHOW_INSIDE_GUIDE' | 'FOCUS_CONFIRMATION';

export interface AgentUiDirective {
  type: AgentUiDirectiveType;
  appointmentId: string | null;
  focus: string | null;
}

/**
 * 需要与普通聊天气泡区分显示的提示块，目前只有医疗越界一种。
 *
 * 只影响展示：不切 stage、不让待确认的操作失效。所以前端渲染时也不能借它
 * 改动办理状态或隐藏确认卡——老人问一句「这个药还能吃吗」不等于想中断办理。
 */
export interface AgentNotice {
  type: string;
  title: string;
  message: string;
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
  task: AgentTaskProgress | null;
  /** 本轮需要朗读的权威文本；为空时退回 reply。 */
  speechText?: string | null;
  uiDirective?: AgentUiDirective | null;
  /** 需要与普通气泡区分显示的提示块；没有提示时为空。 */
  notice?: AgentNotice | null;
}

/** 目前只有医疗越界一种；后端以后加新类型时，前端按未知类型安全忽略。 */
export const MEDICAL_BOUNDARY_NOTICE = 'MEDICAL_BOUNDARY';

export type TravelFocus = 'outside' | 'inside';

export interface AgentTaskProgress {
  active: boolean;
  status: 'NONE' | 'ACTIVE' | 'PAUSED' | 'AWAITING_CONFIRMATION' | 'COMPLETED' | 'CANCELLED';
  currentStage: string;
  summary: string;
  missingField: string | null;
}

export interface GeoPoint {
  longitude: number;
  latitude: number;
}

export interface RouteGuide {
  transport: string;
  durationMinutes: number;
  distanceMeters: number;
  departureAt: string;
  origin: GeoPoint;
  destination: GeoPoint;
  polyline: GeoPoint[];
  steps: string[];
  source: string;
}

export interface FacilityGuide {
  hospitalId: string;
  departmentId: string;
  building: string;
  entrance: string;
  floor: string;
  room: string;
  checkInPoint: string;
  landmark: string | null;
  accessibleRouteHint: string;
  helpDesk: string | null;
  verifiedAt: string;
}

export interface AppointmentTravelGuide {
  appointmentId: string;
  hospital: string;
  department: string;
  appointmentAt: string;
  route: RouteGuide;
  facility: FacilityGuide;
  simulated: boolean;
}

export interface ConversationHistoryResponse {
  conversationId: string;
  /** 会话生命周期状态。CLOSED 的会话只能翻看，不能再办事。 */
  status: 'ACTIVE' | 'CLOSED' | 'EXPIRED';
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
