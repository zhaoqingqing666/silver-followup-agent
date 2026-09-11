import type { AppointmentMaterial } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export async function getAppointmentMaterials(
  appointmentId: string,
  userId = DEMO_USER_ID
): Promise<AppointmentMaterial[]> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/materials`,
    { cache: 'no-store' }
  );
  if (!response.ok) throw new Error('无法读取材料准备状态');
  return response.json();
}

export async function updateAppointmentMaterial(
  appointmentId: string,
  materialId: string,
  status: AppointmentMaterial['status'],
  userId = DEMO_USER_ID
): Promise<AppointmentMaterial> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/materials/${materialId}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status, confirmSource: 'USER', photoUrl: null }),
    }
  );
  if (!response.ok) throw new Error('材料状态没有保存成功，请重试');
  return response.json();
}
