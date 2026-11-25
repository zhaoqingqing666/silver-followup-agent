import type { CareTimelineEvent } from '@/types/domain';

/**
 * 色调 → 样式。就诊动态和消息页共用同一套，免得同一个 danger 在两个页面上是两个颜色。
 *
 * @see CareTimelineEvent 色调取值：info | success | warning | danger
 */
export const TONE_DOT_COLORS: Record<CareTimelineEvent['tone'], string> = {
  info: 'bg-primary',
  success: 'bg-green-500',
  warning: 'bg-amber-400',
  danger: 'bg-red-500',
};

export const TONE_TEXT_COLORS: Record<CareTimelineEvent['tone'], string> = {
  info: 'text-foreground',
  success: 'text-green-800',
  warning: 'text-amber-800',
  danger: 'text-red-700',
};

/** 把 ISO 时间（2026-09-07T19:05:00）格式化为 9月7日 19:05。 */
export function formatDateTime(value: string): string {
  const [date, time] = value.split('T');
  const [, month, day] = date.split('-');
  const hm = (time ?? '').slice(0, 5);
  return `${Number(month)}月${Number(day)}日 ${hm}`;
}

/** 把 ISO 时间格式化为 HH:mm。 */
export function formatHM(value: string): string {
  const time = value.includes('T') ? value.split('T')[1] : value;
  return time.slice(0, 5);
}

/** 把 2026-09-20 格式化为 9月20日。 */
export function formatDate(value: string): string {
  const [, month, day] = value.split('-');
  return `${Number(month)}月${Number(day)}日`;
}

/**
 * 预约状态的标签与配色，列表页和详情页共用一份。
 *
 * 分开写的时候出过这个岔子：详情页把「不是 CONFIRMED」一律写成「已取消」（只认两种状态），
 * 而同一条预约在列表页是「处理中」——同一件事两张页面两个说法。后端一加第三种状态就会露出来，
 * 所以判定只留这一处。
 */
export function appointmentStatusChip(status: string): { label: string; className: string } {
  if (status === 'CONFIRMED') return { label: '已预约', className: 'bg-green-100 text-green-800' };
  if (status === 'CANCELLED') return { label: '已取消', className: 'bg-gray-100 text-gray-600' };
  return { label: '处理中', className: 'bg-amber-100 text-amber-800' };
}
