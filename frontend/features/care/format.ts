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
