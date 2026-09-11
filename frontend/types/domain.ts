export type TabId = 'home' | 'tasks' | 'assistant' | 'profile';

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
  status: string;
  createdAt: string;
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
  /** 用户上传图片时附带的 data URL 列表（最多 3 张），用于显示缩略图 */
  imageDataUrls?: string[];
  /** 本条消息是否来自语音输入（前端显示🎤标记，后端更积极确认） */
  isVoice?: boolean;
  /** 语音消息的 audio URL（像微信一样可点击回放） */
  audioUrl?: string;
  /** 语音时长（秒），用于气泡宽度 */
  audioDuration?: number;
  /** 语音转文字的状态：转写中/已完成/失败 */
  voiceState?: 'transcribing' | 'done' | 'error';
}

/** 会话状态：ACTIVE 可以继续聊；CLOSED 已经结束，只能查看 */
export type ConversationStatus = 'ACTIVE' | 'CLOSED';

/** 历史对话列表里的一条摘要 */
export interface ConversationSummary {
  id: string;
  title: string;
  stage: string;
  updatedAt: string;
  status: ConversationStatus;
  messageCount: number;
}

export interface ToolTrace {
  /** 工具的真实标识，如 drug.queryKnowledge */
  toolName: string;
  /** 面向老人的中文工具名，如"药品知识查询"，由后端按真实工具名推导 */
  label: string;
  /** 模型运行时真实生成的调用参数（JSON 字符串） */
  parameters: string;
  /** 工具真实执行后的原始返回结果（JSON 字符串） */
  result: string;
  /** 由真实结果概括出的一句人话，不改写事实 */
  summary: string;
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
  materials: string[];
  departureTime: string;
  familyContact: string;
}

export interface AgentConfirmationCard {
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
  /** 后端指示前端自动执行的动作，如 OPEN_TASKS 自动跳转事项页 */
  autoAction?: string;
}

export interface ConversationHistoryResponse {
  conversationId: string;
  stage: string;
  /** ACTIVE 可以继续聊；CLOSED 已经结束，只能查看 */
  status: ConversationStatus;
  messages: Array<{
    id: number;
    role: 'assistant' | 'user';
    content: string;
    createdAt: string;
  }>;
  current: AgentTurnResponse;
}

export interface AppointmentMaterial {
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
