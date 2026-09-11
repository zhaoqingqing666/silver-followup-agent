import type { AppointmentSummary, AppointmentTravelGuide, UserProfile, VoicePreference } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export async function getAppointments(userId = DEMO_USER_ID): Promise<AppointmentSummary[]> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/appointments`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取复诊事项');
  return response.json();
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
