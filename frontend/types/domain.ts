export type TabId = 'home' | 'tasks' | 'assistant' | 'profile';

/** 后端 appointments.status 的取值；比较时用这里的常量，不要另写字符串。 */
export const APPOINTMENT_STATUS = {
  CONFIRMED: 'CONFIRMED',
  CANCELLED: 'CANCELLED',
} as const;

/** 后端 appointment_materials.status 的取值。 */
export const MATERIAL_STATUS = {
  NOT_PREPARED: 'NOT_PREPARED',
  PREPARED: 'PREPARED',
  PHOTO_CONFIRMED: 'PHOTO_CONFIRMED',
} as const;

export type MaterialStatus = (typeof MATERIAL_STATUS)[keyof typeof MATERIAL_STATUS];

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
}

export interface MaterialItem {
  id: string;
  appointmentId: string;
  materialCode: string;
  materialName: string;
  required: boolean;
  status: MaterialStatus;
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

/**
 * 需要与普通气泡区分显示的提示，目前只有医疗越界。
 * 只影响展示：不改变 stage，也不让已经生成的确认凭据失效。
 */
export interface AgentNotice {
  type: 'MEDICAL_BOUNDARY' | string;
  title: string;
  message: string;
}

export interface AgentTurnResponse {
  conversationId: string;
  stage: string;
  reply: string;
  /** 询问就诊医院时为空，用户通过语音或文字回答。 */
  quickReplies: AgentQuickReply[];
  plan: AgentPlanCard | null;
  confirmation: AgentConfirmationCard | null;
  result: AgentResultCard | null;
  toolTraces: ToolTrace[];
  notice: AgentNotice | null;
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
