import type { HealthMemo, MemoRepeat } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** 备忘分两类：timed=到点提醒的（当天的，攒不起来）；standing=长期备忘（只增不减，会越攒越多）。 */
export type MemoKind = 'timed' | 'standing';

export interface MemoCounts { timed: number; standing: number; }

/**
 * 取一类备忘。
 *
 * @param limit  最多几条；0（缺省）表示不限——提醒页要一次拿全，长期备忘页才分页。
 * @param offset 跳过前几条：长期备忘页一页页往回翻用。
 */
export async function getMemosByKind(kind: MemoKind, limit = 0, offset = 0,
                                     userId = DEMO_USER_ID): Promise<HealthMemo[]> {
  const response = await fetch(
      `${API_BASE}/api/users/${userId}/memos?kind=${kind}&limit=${limit}&offset=${offset}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取健康备忘');
  return response.json();
}

/** 两类备忘各有几条：首页两个按钮上的条数用，省得为了算条数把长期备忘全拉下来。 */
export async function getMemoCounts(userId = DEMO_USER_ID): Promise<MemoCounts> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/memos/count`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取健康备忘条数');
  return response.json();
}

export async function completeMemo(memoId: string, userId = DEMO_USER_ID): Promise<void> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/memos/${memoId}/done`, { method: 'POST' });
  if (!response.ok) throw new Error('标记备忘失败');
}

export async function deleteMemo(memoId: string, userId = DEMO_USER_ID): Promise<void> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/memos/${memoId}`, { method: 'DELETE' });
  if (!response.ok) throw new Error('删除备忘失败');
}

/**
 * 修改备忘：text 为要记的新内容；remindAt 传 null 表示转成长期备忘（不再提醒）；
 * repeatRule 传 null 表示只提醒一次，否则每天/每周/每月重复。
 */
export async function updateMemo(memoId: string, text: string, remindAt: string | null,
                                 repeatRule: MemoRepeat | null = null, userId = DEMO_USER_ID): Promise<void> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/memos/${memoId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, remindAt, repeatRule }),
  });
  if (!response.ok) throw new Error('修改备忘失败');
}
