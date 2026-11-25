import type {
  BookedAppointment,
  BookingDateWindow,
  BookingDepartment,
  BookingHospital,
  BookingPreview,
  CareElder,
  CareNotification,
  CareRole,
  CareTimelineEvent,
  CreateBookingRequest,
} from '@/types/domain';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** 演示身份 → 照护者账号 id（无真实登录，用环境变量 + 入口选角模拟）。 */
export const CARE_CID_BY_ROLE: Record<CareRole, string> = {
  FAMILY: 'user-f001',
  VOLUNTEER: 'user-v001',
};

async function readJson<T>(url: string, message: string): Promise<T> {
  const response = await fetch(url, { cache: 'no-store' });
  if (!response.ok) throw new Error(message);
  return response.json();
}

/** 把后端 400 {"message": …} 等错误转成可读提示。 */
async function readError(response: Response, fallback: string): Promise<Error> {
  try {
    const body = await response.json();
    if (body && typeof body.message === 'string' && body.message) return new Error(body.message);
  } catch {
    // 非 JSON 响应，走兜底文案
  }
  return new Error(fallback);
}

async function postJson<T>(url: string, body: unknown, fallback: string): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw await readError(response, fallback);
  return response.json();
}

/** 我协同的长辈。 */
export function getCareElders(caregiverId: string): Promise<CareElder[]> {
  return readJson(`${API_BASE}/api/caregivers/${caregiverId}/elders`, '无法读取协同的长辈');
}

/** 某位长辈的就诊动态。 */
export function getElderTimeline(caregiverId: string, elderId: string): Promise<CareTimelineEvent[]> {
  return readJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/timeline`, '无法读取就诊动态');
}

/** 消息：我协同的长辈产生的通知。 */
export function getCareNotifications(caregiverId: string): Promise<CareNotification[]> {
  return readJson(`${API_BASE}/api/caregivers/${caregiverId}/notifications`, '无法读取消息');
}

/** 帮助预约：可选的医院。 */
export function getBookingHospitals(caregiverId: string, elderId: string): Promise<BookingHospital[]> {
  return readJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/hospitals`, '无法读取可选医院');
}

/** 帮助预约：某医院下的科室。 */
export function getBookingDepartments(caregiverId: string, elderId: string, hospitalId: string): Promise<BookingDepartment[]> {
  return readJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/departments?hospitalId=${hospitalId}`, '无法读取可选科室');
}

/** 帮助预约：某科室近期可约日期与号源。 */
export function getBookingWindows(caregiverId: string, elderId: string, hospitalId: string, departmentId: string): Promise<BookingDateWindow[]> {
  return readJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/windows?hospitalId=${hospitalId}&departmentId=${departmentId}`, '无法读取可选日期');
}

/**
 * 帮助预约：算出这次会办理什么并拿到票据，<b>不写任何数据</b>。
 * 三个写操作都是两段式，提交时必须把这里返回的 confirmationId 带回去。
 */
export function prepareBooking(caregiverId: string, elderId: string, request: CreateBookingRequest): Promise<BookingPreview> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/prepare`, request, '无法生成办理确认，请稍后重试');
}

/** 帮助预约：票据核对通过后替就诊人办理一次复诊。 */
export function createBooking(caregiverId: string, elderId: string, request: CreateBookingRequest, confirmationId: string): Promise<BookedAppointment> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book?confirmationId=${encodeURIComponent(confirmationId)}`, request, '代约失败，请稍后重试');
}

/** 改期预览：先说明改完之后是什么样，不写数据。 */
export function prepareModifyBooking(caregiverId: string, elderId: string, request: CreateBookingRequest): Promise<BookingPreview> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/modify/prepare`, request, '无法生成改期确认，请稍后重试');
}

/** 修改该长辈当前这张进行中的预约（原位更新同一条记录，不留“已取消”记录）。 */
export function modifyBooking(caregiverId: string, elderId: string, request: CreateBookingRequest, confirmationId: string): Promise<BookedAppointment> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/modify?confirmationId=${encodeURIComponent(confirmationId)}`, request, '修改预约失败，请稍后重试');
}

/** 只切换当前照护者是否陪同这张进行中的复诊（不动预约本身）。 */
export function setBookingAccompany(caregiverId: string, elderId: string, willAccompany: boolean): Promise<BookedAppointment> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/accompany`, { willAccompany }, '保存陪同状态失败，请稍后重试');
}

/** 取消预览：取消不可逆，先让人看清要取消的是哪一张，不写数据。 */
export function prepareCancelBooking(caregiverId: string, elderId: string): Promise<BookingPreview> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/cancel/prepare`, {}, '无法生成取消确认，请稍后重试');
}

/** 帮助预约：取消该长辈当前进行中的复诊（用于“先取消旧预约，再重新代约”）。 */
export function cancelElderBooking(caregiverId: string, elderId: string, confirmationId: string): Promise<{ message?: string }> {
  return postJson(`${API_BASE}/api/caregivers/${caregiverId}/elders/${elderId}/book/cancel?confirmationId=${encodeURIComponent(confirmationId)}`, {}, '取消预约失败，请稍后重试');
}
