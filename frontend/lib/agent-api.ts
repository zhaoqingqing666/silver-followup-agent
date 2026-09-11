import type { AgentTurnResponse, ConversationHistoryResponse, ConversationSummary } from '@/types/domain';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

async function request(path: string, init?: RequestInit): Promise<AgentTurnResponse> {
  const response = await fetch(API_BASE + path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: '服务暂时不可用' }));
    throw new Error(error.message ?? '服务暂时不可用');
  }
  return response.json();
}

export function startConversation() {
  return request('/api/agent/conversations', { method: 'POST' });
}

export function sendAgentMessage(conversationId: string, message: string, isVoice = false) {
  return request('/api/agent/messages', {
    method: 'POST',
    body: JSON.stringify({ conversationId, message, isVoice }),
  });
}

/** 图片走对话问答：把图片当作一轮用户消息发给后端（最多 3 张），识别+总结由后端完成。 */
export function sendAgentImageMessage(conversationId: string, imageDataUrls: string[], hint = '') {
  return request('/api/agent/images', {
    method: 'POST',
    body: JSON.stringify({ conversationId, imageDataUrls, hint }),
  });
}

export function sendAgentAction(conversationId: string, action: string, value = '') {
  return request('/api/agent/actions', {
    method: 'POST',
    body: JSON.stringify({ conversationId, action, value }),
  });
}

export function confirmAgentActions(conversationId: string, approved: boolean) {
  return request('/api/agent/confirmations', {
    method: 'POST',
    body: JSON.stringify({ conversationId, approved }),
  });
}

export async function getConversationHistory(conversationId: string): Promise<ConversationHistoryResponse> {
  const response = await fetch(API_BASE + '/api/agent/conversations/' + conversationId, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法恢复上次对话');
  return response.json();
}

/** 最近 3 天的历史对话列表（新→旧） */
export async function listConversations(): Promise<ConversationSummary[]> {
  const response = await fetch(API_BASE + '/api/agent/conversations', { cache: 'no-store' });
  if (!response.ok) throw new Error('无法获取历史对话');
  return response.json();
}
