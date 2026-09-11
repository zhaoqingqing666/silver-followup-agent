/**
 * 历史记录里的时间说法。
 *
 * 「2026-09-11T12:03:08.123」这种原文对老人等于没写——他判断不了那是刚才还是上礼拜。
 * 换成「刚刚」「昨天 09:10」他才知道这是多久以前的事，要不要点进去。
 *
 * 纯函数（只依赖传进来的 now），可以直接跑用例把边界验一遍。
 */

const pad = (value: number) => String(value).padStart(2, '0');

function clock(at: Date): string {
  return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/** 两个时刻之间隔了几个「日历天」。按零点算，不按 24 小时算——
 *  昨晚 23:50 到今天 00:10 只差 20 分钟，但确实是「今天」的事了。 */
function calendarDaysBetween(at: Date, now: Date): number {
  const a = new Date(at.getFullYear(), at.getMonth(), at.getDate());
  const b = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((b.getTime() - a.getTime()) / 86_400_000);
}

export function relativeTime(iso: string, now: Date = new Date()): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return '';
  const minutes = Math.floor((now.getTime() - at.getTime()) / 60_000);

  // 时钟比服务器快一点时会算出「-1 分钟前」。说「刚刚」前后矛盾，直接给钟点。
  if (minutes < 0) return clock(at);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟前`;

  const days = calendarDaysBetween(at, now);
  if (days <= 0) return `今天 ${clock(at)}`;
  if (days === 1) return `昨天 ${clock(at)}`;
  if (days < 7) return `${days} 天前`;
  return `${at.getMonth() + 1}月${at.getDate()}日`;
}

/**
 * 历史列表上的状态徽标。
 *
 * CLOSED 与 EXPIRED 在界面上必须分开说：前者是真的结束了，点进去只能翻看；
 * 后者只是放久了，点进去还能接着说。都写「已结束」，老人就会以为这段再也回不去了。
 */
export function statusLabel(status: string): string {
  if (status === 'CLOSED') return '已结束';
  if (status === 'EXPIRED') return '很久没聊';
  return '进行中';
}

/** 徽标配色：已结束压暗，很久没聊给个温和的提醒色，进行中才是主色。 */
export function statusTone(status: string): string {
  if (status === 'CLOSED') return 'bg-[#efece7] text-[#6b6255]';
  if (status === 'EXPIRED') return 'bg-[#fff0dc] text-[#a8641c]';
  return 'bg-[#e9f5ea] text-[#2f6b38]';
}
