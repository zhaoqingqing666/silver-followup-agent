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
  /** 询问就诊医院时为空，用户通过语音或文字回答。 */
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
