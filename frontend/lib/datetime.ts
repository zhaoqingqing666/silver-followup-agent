/**
 * 后端日期时间的展示转换。
 * 集中在这里，避免各个页面各自用下标切片，切错位置也不会有人发现。
 */

/** "2026-09-16" → 月份数字。格式不符时返回 0，由页面决定怎么显示。 */
export function monthOfYear(value: string): number {
  return Number(value.split('-')[1]) || 0;
}

/** "2026-09-16" → 日数字。格式不符时返回 0。 */
export function dayOfMonth(value: string): number {
  return Number(value.split('-')[2]) || 0;
}

/** "2026-09-16" → "9月16日"。 */
export function formatDate(value: string): string {
  return `${monthOfYear(value)}月${dayOfMonth(value)}日`;
}

/** "10:30:00" 与 "2026-09-16T10:30:00" 都 → "10:30"。 */
export function formatTime(value: string): string {
  const separator = value.indexOf('T');
  const time = separator >= 0 ? value.slice(separator + 1) : value;
  return time.slice(0, 5);
}
