import type { AppointmentSummary } from '@/types/domain';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export async function getAppointments(userId = 'user-001'): Promise<AppointmentSummary[]> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/appointments`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取复诊事项');
  return response.json();
}
