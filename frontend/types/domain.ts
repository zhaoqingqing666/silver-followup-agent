export type TabId = 'home' | 'tasks' | 'assistant' | 'profile' | 'travel';

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

/** 后端封闭枚举；前端遇到未知 type 必须安全忽略，不跳空白页。 */
export type AgentUiDirectiveType =
  | 'NONE' | 'OPEN_ASSISTANT' | 'OPEN_TASKS' | 'OPEN_MATERIALS' | 'OPEN_TRAVEL'
  | 'SHOW_OUTSIDE_ROUTE' | 'SHOW_INSIDE_GUIDE' | 'FOCUS_CONFIRMATION';

export interface AgentUiDirective {
  type: AgentUiDirectiveType;
  appointmentId: string | null;
  focus: string | null;
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
}

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
