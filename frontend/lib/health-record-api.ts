import type { FamilyContact, HealthRecord } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/**
 * 老人端健康记录：助手帮老人记下的实测数值（按测量时间倒序）。
 *
 * @param limit  最多几条，缺省 3 条（首页只露最近几条）。
 * @param offset 跳过前几条：记录页一页页往回翻历史用。
 */
export async function getHealthRecords(limit = 3, offset = 0, userId = DEMO_USER_ID): Promise<HealthRecord[]> {
  const response = await fetch(
      `${API_BASE}/api/users/${userId}/health-records?limit=${limit}&offset=${offset}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取健康记录');
  return response.json();
}

/** 一共记了多少条：首页按钮上的“共N条”用，也用来判断后面还有没有更早的。 */
export async function getHealthRecordTotal(userId = DEMO_USER_ID): Promise<number> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/health-records/count`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取健康记录条数');
  const body: { total?: number } = await response.json();
  return body.total ?? 0;
}

/**
 * 主联系人：记录页那个按钮要显示“发给女儿 小丽”。
 * 没配家属联系人时后端给回 null——这不是出错，是还没配置。
 * 电话是后端脱敏后的掩码，不含明文。
 */
export async function getFamilyContact(userId = DEMO_USER_ID): Promise<FamilyContact | null> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/family-contact`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取家属联系人');
  const body: { contact?: FamilyContact | null } = await response.json();
  return body.contact ?? null;
}

/** 汇总多长时间；没点名项目就是一整段记录。 */
export type HealthReportRange = 'week' | 'month';

/**
 * 把健康记录汇总后发给家属。
 *
 * @param message 真发出去的那段话（页面原样给老人看一遍：发了什么，心里有数）；没发出去时为 null。
 * @param reason  没发出去时的原因，能直接念给老人听。
 */
export interface HealthReportResult {
  sent: boolean;
  reason: string | null;
  contactLabel: string | null;
  rangeLabel: string | null;
  recordCount: number;
  message: string | null;
}

export async function sendHealthReport(range: HealthReportRange, userId = DEMO_USER_ID): Promise<HealthReportResult> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/health-report?range=${range}`,
      { method: 'POST', cache: 'no-store' });
  if (!response.ok) throw new Error('发送失败');
  return response.json();
}
