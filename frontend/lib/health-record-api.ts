import type { FamilyContact, HealthRecord } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/**
 * 记录页顶部那排筛选按钮用的项目词表。
 *
 * 后端 HealthRecordParser 不管老人说的是“高压”“收缩压”还是“血压”，
 * 都按同一张表归成标准项目名存下来，所以库里有的就是这六个，按钮也就这六个。
 */
export const RECORD_ITEMS = ['血压', '血糖', '心率', '体温', '体重', '血氧'] as const;

/**
 * 老人端健康记录：助手帮老人记下的实测数值（按测量时间倒序）。
 *
 * @param item   只取某一个项目；null/空表示不限项目（“全部”）。
 * @param limit  最多几条，缺省 3 条（首页只露最近几条）。
 * @param offset 跳过前几条：记录页一页页往回翻历史用。
 */
export async function getHealthRecords(limit = 3, offset = 0, item: string | null = null,
                                       userId = DEMO_USER_ID): Promise<HealthRecord[]> {
  const filter = item ? `&item=${encodeURIComponent(item)}` : '';
  const response = await fetch(
      `${API_BASE}/api/users/${userId}/health-records?limit=${limit}&offset=${offset}${filter}`,
      { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取健康记录');
  return response.json();
}

/** 一共记了多少条：首页按钮上的“共N条”用，也用来判断后面还有没有更早的。筛选时要传同一个 item。 */
export async function getHealthRecordTotal(item: string | null = null, userId = DEMO_USER_ID): Promise<number> {
  const filter = item ? `?item=${encodeURIComponent(item)}` : '';
  const response = await fetch(`${API_BASE}/api/users/${userId}/health-records/count${filter}`, { cache: 'no-store' });
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

/**
 * 汇总一段时间的记录发给家属。
 *
 * @param item 只发某一个项目；null/空表示全部项目。
 *             页面筛着“血压”时发的就该只有血压——筛了又全发，老人看着是血压、
 *             家属收到的却是全部，两边说的不是一回事。发不出去时的回话也会点名项目
 *             （“最近一周没有血压记录”）。
 */
export async function sendHealthReport(range: HealthReportRange, item: string | null = null,
                                      userId = DEMO_USER_ID): Promise<HealthReportResult> {
  const filter = item ? `&item=${encodeURIComponent(item)}` : '';
  const response = await fetch(`${API_BASE}/api/users/${userId}/health-report?range=${range}${filter}`,
      { method: 'POST', cache: 'no-store' });
  if (!response.ok) throw new Error('发送失败');
  return response.json();
}
