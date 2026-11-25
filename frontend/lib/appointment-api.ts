import type { AppointmentSummary, AppointmentTravelGuide, UserProfile, VoicePreference } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export async function getAppointments(userId = DEMO_USER_ID): Promise<AppointmentSummary[]> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/appointments`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取复诊事项');
  return response.json();
}

/**
 * 取消一条已预约的记录。
 *
 * <p>后端走的是助手那条同款取消链（释放号源、停掉关联提醒、置为 CANCELLED），
 * 记录本身不删——所以调用方拿到的新列表里不会再有它，但库里的痕迹还在。
 *
 * <p>失败时把后端的 { message } 原样抛出来：那句话是给老人看的（「已经不是已预约状态了」
 * 之类），比前端统一写一句「操作失败」有用得多。
 */
export async function cancelAppointment(appointmentId: string, userId = DEMO_USER_ID): Promise<void> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/cancel`,
    { method: 'POST' },
  );
  if (response.ok) return;
  const body: unknown = await response.json().catch(() => null);
  const message = (body as { message?: string } | null)?.message;
  throw new Error(message || '取消没有成功，请稍后再试。');
}

export async function getAppointmentTravelGuide(
  appointmentId: string,
  userId = DEMO_USER_ID,
): Promise<AppointmentTravelGuide> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/travel-guide`,
    { cache: 'no-store' },
  );
  if (!response.ok) throw new Error('无法读取这条预约的出行和院内指引');
  return response.json();
}

export async function getUserProfile(userId = DEMO_USER_ID): Promise<UserProfile> {
  const response = await fetch(`${API_BASE}/api/users/${userId}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取用户资料');
  return response.json();
}

export async function getVoicePreference(userId = DEMO_USER_ID): Promise<VoicePreference> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/preferences`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取语音设置');
  return response.json();
}

/**
 * 保存语音设置。三个字段都可选，未提供的后端保持原值——
 * 所以「自动朗读」开关和朗读设置面板里的「语速」互不覆盖。
 */
export async function updateVoicePreference(
  patch: { autoSpeakEnabled?: boolean; speechRate?: number; speechVolume?: number },
  userId = DEMO_USER_ID,
): Promise<VoicePreference> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/preferences`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) throw new Error('语音设置没有保存成功');
  return response.json();
}
