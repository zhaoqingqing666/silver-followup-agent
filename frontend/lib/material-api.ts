import { API_BASE_URL, DEMO_USER_ID } from '@/lib/app-config';
import type { MaterialItem } from '@/types/domain';

export async function getAppointmentMaterials(
  appointmentId: string,
  userId = DEMO_USER_ID,
): Promise<MaterialItem[]> {
  const response = await fetch(
    `${API_BASE_URL}/api/users/${userId}/appointments/${appointmentId}/materials`,
    { cache: 'no-store' },
  );
  if (!response.ok) throw new Error('无法读取材料准备状态');
  return response.json();
}

export async function updateAppointmentMaterial(
  appointmentId: string,
  materialId: string,
  status: MaterialItem['status'],
  userId = DEMO_USER_ID,
): Promise<MaterialItem> {
  const response = await fetch(
    `${API_BASE_URL}/api/users/${userId}/appointments/${appointmentId}/materials/${materialId}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status, confirmSource: 'USER', photoUrl: null }),
    },
  );
  if (!response.ok) throw new Error('材料状态没有保存成功，请重试');
  return response.json();
}
