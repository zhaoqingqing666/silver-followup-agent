import type { CareActor } from '@/types/domain';

export const DEMO_USER_ID = process.env.NEXT_PUBLIC_DEMO_USER_ID ?? 'user-001';

/**
 * 构建期固定的演示身份（构建环境变量 NEXT_PUBLIC_DEMO_ACTOR）。
 * 设为 'ELDER' | 'FAMILY' | 'VOLUNTEER' 可跳过入口选角直进对应视角；
 * 留空则每次进入都显示身份选择。
 */
export const DEMO_ACTOR = process.env.NEXT_PUBLIC_DEMO_ACTOR as CareActor | undefined;
