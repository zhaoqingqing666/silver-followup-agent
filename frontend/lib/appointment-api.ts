import type { AppointmentSummary, UserProfile } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export async function getAppointments(userId = DEMO_USER_ID): Promise<AppointmentSummary[]> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/appointments`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取复诊事项');
  return response.json();
}

export async function getUserProfile(userId = DEMO_USER_ID): Promise<UserProfile> {
  const response = await fetch(`${API_BASE}/api/users/${userId}`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取用户资料');
  return response.json();
}
