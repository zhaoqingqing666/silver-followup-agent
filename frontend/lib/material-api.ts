import { DEMO_USER_ID } from '@/lib/app-config';
import type { MaterialItem } from '@/types/domain';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

export async function getAppointmentMaterials(
  appointmentId: string,
  userId = DEMO_USER_ID,
): Promise<MaterialItem[]> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/materials`,
    { cache: 'no-store' },
  );
  if (!response.ok) throw new Error('无法读取材料准备状态');
  return response.json();
}

/** 把后端 400 {"message": …} 转成可读提示：「照片太大」这类原因要原样带给老人。 */
async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = await response.json();
    if (body && typeof body.message === 'string' && body.message) return new Error(body.message);
  } catch {
    // 非 JSON 响应，走兜底文案
  }
  return new Error(fallback);
}

async function patchMaterial(
  appointmentId: string,
  materialId: string,
  userId: string,
  body: { status: MaterialItem['status']; confirmSource: string; photoUrl: string | null },
  fallback: string,
): Promise<MaterialItem> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/materials/${materialId}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    },
  );
  if (!response.ok) throw await readError(response, fallback);
  return response.json();
}

export function updateAppointmentMaterial(
  appointmentId: string,
  materialId: string,
  status: MaterialItem['status'],
  userId = DEMO_USER_ID,
): Promise<MaterialItem> {
  return patchMaterial(appointmentId, materialId, userId,
    { status, confirmSource: 'USER', photoUrl: null }, '材料状态没有保存成功，请重试');
}

/**
 * 取回这项材料拍过的照片（data URL）。
 * 404 表示这项还没拍过照——这是「还没有照片」，不是出错，返回 null 让页面自己决定怎么显示。
 */
export async function getMaterialPhoto(
  appointmentId: string,
  materialId: string,
  userId = DEMO_USER_ID,
): Promise<string | null> {
  const response = await fetch(
    `${API_BASE}/api/users/${userId}/appointments/${appointmentId}/materials/${materialId}/photo`,
    { cache: 'no-store' },
  );
  if (response.status === 404) return null;
  if (!response.ok) throw await readError(response, '照片没有读到，请重试');
  const body: { dataUrl?: string } = await response.json();
  return body.dataUrl ?? null;
}

/**
 * 拍照确认：dataUrl 是压缩后的照片本体。
 * 后端只把短引用写进 photo_url，图片本体落进附件表——列表接口因此不会被 base64 撑大。
 */
export function confirmMaterialWithPhoto(
  appointmentId: string,
  materialId: string,
  dataUrl: string,
  userId = DEMO_USER_ID,
): Promise<MaterialItem> {
  return patchMaterial(appointmentId, materialId, userId,
    { status: 'PHOTO_CONFIRMED', confirmSource: 'PHOTO', photoUrl: dataUrl },
    '照片确认没有保存成功，请重试');
}
