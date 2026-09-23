import type { HealthProfile } from '@/types/domain';
import { DEMO_USER_ID } from '@/lib/app-config';

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** 家属端「长辈信息」和老人端「我的」调的是同一个地址，所以谁改的对方都看得到。 */
export async function getHealthProfile(userId = DEMO_USER_ID): Promise<HealthProfile> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/health-profile`, { cache: 'no-store' });
  if (!response.ok) throw new Error('无法读取健康档案');
  return response.json();
}

/**
 * 要保存的四个字段，外加「是谁填的」。
 *
 * 身高体重在这里是字符串（不是数字），因为要跟另外两项共用一个口径：
 * **null = 这一项没动，空串 = 清掉，有值 = 改成它**。写死成 number 的话，「清空身高」
 * 就没法表达，只能让用户把 158 改成 0 这种明显是错的数。
 */
export interface HealthProfilePatch {
  heightCm?: string | null;
  weightKg?: string | null;
  allergies?: string | null;
  medicalHistory?: string | null;
  /** 谁在改，只用来显示「最近是谁填的」。 */
  editorName: string;
}

/**
 * 保存。
 *
 * 只会覆盖真正改过的那几项，两个人先后各改一项不会互相抹掉——所以页面上
 * 把四项一起提交（改的那项是新值，没动的项原样回传）也是安全的。
 *
 * 报错要用后端那句原话（「身高请在50到250厘米之间」），不能一律写成「保存失败」：
 * 老人和家属填错了得知道错在哪。
 */
export async function saveHealthProfile(
    patch: HealthProfilePatch,
    userId = DEMO_USER_ID): Promise<HealthProfile> {
  const response = await fetch(`${API_BASE}/api/users/${userId}/health-profile`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: '' }));
    throw new Error(error.message || '健康档案没有保存成功');
  }
  return response.json();
}
