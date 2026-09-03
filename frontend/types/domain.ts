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
