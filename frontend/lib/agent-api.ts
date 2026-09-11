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

/**
 * 发图片。imageDataUrls 是压缩后的 data URL（后端最多取 3 张）；
 * hint 是老人随图说的那句话，可以为空——后端会退回一句默认问法。
 * 后端不下发图片本身，所以气泡里的图由前端自己持有的这份 data URL 渲染。
 */
export function sendAgentImageMessage(conversationId: string, imageDataUrls: string[], hint = '') {
  return request('/api/agent/images', {
    method: 'POST',
    body: JSON.stringify({ conversationId, imageDataUrls, hint }),
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

/** 一轮进行中的一步：模型生成了什么参数、哪个工具返回了什么。 */
export type TurnProgressEvent = {
  seq: number;
  kind: 'UNDERSTANDING' | 'PLANNING' | 'TOOL_PROPOSED' | 'TOOL_RESULT' | 'ANSWERING';
  text: string;
  tool: string | null;
  /** 工具入参的 JSON 原文，后端已脱敏（手机号、图片 base64 都换掉了） */
  parameters: string | null;
  result: string | null;
  success: boolean | null;
  at: string;
};

export type TurnProgressSnapshot = { active: boolean; events: TurnProgressEvent[] };

/**
 * 看一眼这一轮办到哪了。只读、只读内存，进程重启就没了——
 * 它证明的是「此刻正在真实调用」，而不是「曾经调用过」（后者看 toolTraces）。
 * 用 afterSeq 增量拉，轮询时才不会把整轮过程重传一遍。
 */
export async function getTurnProgress(
  conversationId: string, afterSeq = 0, signal?: AbortSignal,
): Promise<TurnProgressSnapshot> {
  const response = await fetch(
    `${API_BASE}/api/agent/conversations/${conversationId}/progress?afterSeq=${afterSeq}`,
    { cache: 'no-store', signal },
  );
  if (!response.ok) throw new Error('无法读取办理进度');
  return response.json();
}

export async function getConversationHistory(conversationId: string): Promise<ConversationHistoryResponse> {
  const response = await fetch(API_BASE + '/api/agent/conversations/' + conversationId, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法恢复上次对话');
  return response.json();
}

/** 历史记录里的一行。只带列表要显示的字段，想细看再按 id 拉整段。 */
export type ConversationSummary = {
  conversationId: string;
  title: string;
  status: 'ACTIVE' | 'CLOSED' | 'EXPIRED';
  stage: string;
  messageCount: number;
  updatedAt: string;
};

/** 最近聊过的几段对话，新的在前。 */
export async function listConversations(userId = DEMO_USER_ID, limit = 20): Promise<ConversationSummary[]> {
  const query = new URLSearchParams({ userId, limit: String(limit) });
  const response = await fetch(`${API_BASE}/api/agent/conversations?${query}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('暂时打不开历史记录');
  return response.json();
}

/**
 * 结束一段对话。「新对话」先关掉旧的再建新的。
 * 幂等：对已经结束的会话再调一次也不报错，所以前端可以放心重试。
 */
export async function closeAgentConversation(conversationId: string): Promise<void> {
  const response = await fetch(`${API_BASE}/api/agent/conversations/${conversationId}/close`, { method: 'POST' });
  if (!response.ok) throw new Error('暂时无法结束这段对话');
}

/**
 * 助手跨对话记住的事。
 * 这些不是模型随手记的，是老人自己确认过、办成了的预约里沉淀下来的偏好，
 * 所以可以在「我的」页面原样摆给他看，也可以由他逐条忘掉。
 */
export type AgentMemory = {
  key: string;
  kind: string;
  content: string;
  source: string;
  updatedAt: string;
};

export async function listMemories(userId = DEMO_USER_ID): Promise<AgentMemory[]> {
  const query = new URLSearchParams({ userId });
  const response = await fetch(`${API_BASE}/api/agent/memories?${query}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('暂时读不到助手记住的事');
  return response.json();
}

/** 忘掉一条。软删除，后端返回 204；再忘一次也不报错。 */
export async function forgetMemory(key: string, userId = DEMO_USER_ID): Promise<void> {
  const query = new URLSearchParams({ key, userId });
  const response = await fetch(`${API_BASE}/api/agent/memories?${query}`, { method: 'DELETE' });
  if (!response.ok) throw new Error('暂时没能忘掉这条');
}
