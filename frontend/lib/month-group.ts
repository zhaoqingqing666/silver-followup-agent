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

const WEEKDAY_TEXT = '日一二三四五六';
const pad2 = (n: number) => String(n).padStart(2, '0');

/**
 * "2026-09-10T08:00:00" → "9月10日 周三"；今天/昨天给更短的叫法。
 *
 * <p>日期串是本地时间、不带时区（后端就这么给的），所以拿年月日拼 Date 取星期，
 * 不要 `new Date(iso)`——那个在有些浏览器上按 UTC 解析，跨零点会差一天。
 */
function dayTitle(iso: string): string {
  const [date] = iso.split('T');
  const [year, month, day] = date.split('-').map(Number);
  if (!year || !month || !day) return date;
  const weekday = WEEKDAY_TEXT[new Date(year, month - 1, day).getDay()];
  const now = new Date();
  const today = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  const yesterdayKey = `${yesterday.getFullYear()}-${pad2(yesterday.getMonth() + 1)}-${pad2(yesterday.getDate())}`;
  if (date === today) return `今天 周${weekday}`;
  if (date === yesterdayKey) return `昨天 周${weekday}`;
  return `${Number(month)}月${Number(day)}日 周${weekday}`;
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

export interface DayGroup<T> {
  /** "2026-09-10"：切段用的键 */
  key: string;
  /** "9月10日 周三"，今天/昨天另有叫法：给人看的小标题 */
  title: string;
  rows: T[];
}

/**
 * 按“天”把一串记录切成段，最近的一天在前。用在月段里面。
 *
 * <p>一天量好几次血压时，平铺着排会占去好几屏，滚半天看不出“这一天量了几回”；
 * 按天收拢之后，日期上提到小标题里，每条卡片就只剩钟点和数值了。
 *
 * <p>和 {@link groupByMonth} 一样，前提是 rows 已经按时间倒序排好，所以顺序扫一遍就够。
 */
export function groupByDay<T>(rows: T[], isoOf: (row: T) => string): DayGroup<T>[] {
  const groups: DayGroup<T>[] = [];
  for (const row of rows) {
    const iso = isoOf(row);
    const key = iso.slice(0, 10); // "2026-09-10T08:00:00" → "2026-09-10"
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.rows.push(row);
    else groups.push({ key, title: dayTitle(iso), rows: [row] });
  }
  return groups;
}

/** 只要钟点："2026-09-10T08:00:00" → "08:00"。日期已经在小标题里了。 */
export function timeOfDay(iso: string): string {
  const [, time] = iso.split('T');
  return time ? time.slice(0, 5) : '';
}
