import type { AgentTurnResponse, ConversationHistoryResponse } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

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

/**
 * 建会话。userId 是这次会话「服务谁」，actorId 是「谁在操作」。
 * 本人自办时不传 actorId；家属/志愿者代他人办理时两个都要传，
 * 后端会拿 (actorId, userId) 去关系表核对，没绑定的长辈一律拒绝。
 */
export function startConversation(userId = DEMO_USER_ID, actorId?: string) {
  const query = new URLSearchParams({ userId });
  if (actorId) query.set('actorId', actorId);
  return request('/api/agent/conversations?' + query.toString(), { method: 'POST' });
}

export function sendAgentMessage(conversationId: string, message: string) {
  return request('/api/agent/messages', {
    method: 'POST',
    body: JSON.stringify({ conversationId, message }),
  });
}

export function sendAgentAction(conversationId: string, action: string, value = '', label = '') {
  return request('/api/agent/actions', {
    method: 'POST',
    body: JSON.stringify({ conversationId, action, value, label }),
  });
}

export function confirmAgentActions(conversationId: string, approved: boolean, confirmationId: string) {
  return request('/api/agent/confirmations', {
    method: 'POST',
    body: JSON.stringify({ conversationId, approved, confirmationId }),
  });
}

export async function getConversationHistory(conversationId: string): Promise<ConversationHistoryResponse> {
  const response = await fetch(API_BASE + '/api/agent/conversations/' + conversationId, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法恢复上次对话');
  return response.json();
}
