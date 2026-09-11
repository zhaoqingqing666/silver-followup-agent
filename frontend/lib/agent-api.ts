import type { AgentTurnResponse } from '@/types/domain';
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

export function startConversation() {
  return request('/api/agent/conversations?userId=' + encodeURIComponent(DEMO_USER_ID), { method: 'POST' });
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
