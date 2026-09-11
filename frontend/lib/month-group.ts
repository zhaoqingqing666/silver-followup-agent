export interface MonthGroup<T> {
  /** "2026-09"：切段用的键 */
  key: string;
  /** "2026年9月"：给人看的小标题 */
  title: string;
  rows: T[];
}

/** "2026-09" → "2026年9月"；认不出就原样返回，不至于把整段标题弄没。 */
function monthTitle(key: string): string {
  const [year, month] = key.split('-');
  if (!year || !month) return key;
  return `${year}年${Number(month)}月`;
}

/**
 * 按“年月”把一串记录切成段，最新的月份在前。
 *
 * <p>前提是 rows 已经按时间倒序排好了（后端就是这么给的）——所以顺序扫一遍就够，
 * 不用真的分组再排序。翻页时新来的一批更早的记录直接接在后面，同一个月的两段会
 * 在下次渲染时并回一段：页面数的是“已经拿到的全部记录”，不是每一页各切一次。
 */
export function groupByMonth<T>(rows: T[], isoOf: (row: T) => string): MonthGroup<T>[] {
  const groups: MonthGroup<T>[] = [];
  for (const row of rows) {
    const key = isoOf(row).slice(0, 7); // "2026-09-10T08:00:00" → "2026-09"
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.rows.push(row);
    else groups.push({ key, title: monthTitle(key), rows: [row] });
  }
  return groups;
}
