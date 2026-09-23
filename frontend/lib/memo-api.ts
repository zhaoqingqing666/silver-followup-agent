import type { HealthMemo, MemoRepeat } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/**
 * 后端出错时会回 400 {"message": "…"}，那句话是写给人看的（“这条备忘不存在或已经处理过了”），
 * 比前端自己编的兜底文案有用得多——老人看到才知道是这条已经不在了，而不是网络坏了。
 */
async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = await response.json();
    if (body && typeof body.message === 'string' && body.message) return new Error(body.message);
  } catch {
    // 非 JSON 响应，走兜底文案
  }
  return new Error(fallback);
}

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

/**
 * 处理掉一条备忘，返回处理后的这条。
 *
 * <p>重复提醒的不会被结束，而是顺延到下一次，所以返回的 {@code remindAt} 是**新的**那个——
 * 界面要拿它告诉老人“下次提醒：X月X日 08:00”。一次性提醒返回的 {@code status} 是 DONE。
 */
export async function completeMemo(memoId: string, userId = DEMO_USER_ID): Promise<HealthMemo> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/memos/${memoId}/done`, { method: 'POST' });
  if (!response.ok) throw await readError(response, '标记备忘失败');
  return response.json();
}

export async function deleteMemo(memoId: string, userId = DEMO_USER_ID): Promise<void> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/memos/${memoId}`, { method: 'DELETE' });
  if (!response.ok) throw await readError(response, '删除备忘失败');
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
  if (!response.ok) throw await readError(response, '修改备忘失败');
}
