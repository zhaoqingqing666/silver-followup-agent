'use client';

import { useEffect, useState } from 'react';
import { Activity, BellRing, CalendarCheck2, ChevronRight, ClipboardCheck, Headphones, HeartHandshake, Mic, NotebookPen, Route } from 'lucide-react';
import { getAppointments, getUserProfile } from '@/lib/appointment-api';
import { getHealthRecordTotal } from '@/lib/health-record-api';
import { getMemoCounts, getMemosByKind } from '@/lib/memo-api';
import { fmtRemind, fmtRepeat, MemoCard } from '@/features/records/memo-card';
import type { AppointmentSummary, HealthMemo, RecordPage, TabId } from '@/types/domain';

interface HomeViewProps {
  onNavigate: (tab: TabId) => void;
  onOpenTravel: (appointmentId?: string) => void;
  /** 打开首页点进去的二级页（提醒／长期备忘／健康记录）。 */
  onOpenPage: (page: RecordPage) => void;
}

const DUE_WINDOW_MS = 6 * 60 * 60 * 1000; // 到点后 6 小时内仍提醒（不提前弹）

/** 重复备忘“今天的到点”：每天=今天那一刻；每周=本周同星期几；每月=本月同号（本月没有这天则为 null）。 */
function nextOccurrence(remindAt: string, repeatRule: HealthMemo['repeatRule'], now: Date): Date | null {
  const at = new Date(remindAt);
  if (!repeatRule) return at;
  const hours = at.getHours();
  const minutes = at.getMinutes();
  if (repeatRule === 'DAILY') {
    return new Date(now.getFullYear(), now.getMonth(), now.getDate(), hours, minutes);
  }
  if (repeatRule === 'WEEKLY') {
    const delta = (at.getDay() - now.getDay() + 7) % 7;
    return new Date(now.getFullYear(), now.getMonth(), now.getDate() + delta, hours, minutes);
  }
  const monthly = new Date(now.getFullYear(), now.getMonth(), at.getDate(), hours, minutes);
  return monthly.getDate() === at.getDate() ? monthly : null;
}

/**
 * 首页只露一条提醒，露哪一条由这里定：算出每条“最近的一次到点”，谁小谁露。
 *
 * <p>不能直接拿 remindAt 比大小——重复备忘存的是第一次说的那个时间（锚点），
 * 比如“每天八点”是上周设的，锚点早就过去了，可它下一次其实是明天早上。
 *
 * <p>还没到点的，用那个时刻；已经到点但还在提醒窗口内的，也算（到点横幅正在提醒的
 * 就是这一条，一条提醒不该在首页出现两种说法）；过窗口太久的，重复的往后推一个周期，
 * 一次性的没有下回了，排到最后——它该在提醒页里躺着，不该占着首页。
 */
function nearestDueAt(memo: HealthMemo, now: Date): number {
  const occurrence = nextOccurrence(memo.remindAt as string, memo.repeatRule, now);
  if (!occurrence) return Number.MAX_SAFE_INTEGER; // 本月没有这一号（例如每月31号）
  const at = occurrence.getTime();
  if (at - now.getTime() > -DUE_WINDOW_MS) return at; // 将来到点，或刚过点还能提醒
  if (!memo.repeatRule) return Number.MAX_SAFE_INTEGER; // 一次性且早过期
  const advanced = new Date(at);
  // 最多推 24 个周期（每月一次 = 两年）就够；防的是数据异常时死循环
  for (let step = 0; step < 24 && advanced.getTime() <= now.getTime(); step++) {
    if (memo.repeatRule === 'DAILY') advanced.setDate(advanced.getDate() + 1);
    else if (memo.repeatRule === 'WEEKLY') advanced.setDate(advanced.getDate() + 7);
    else advanced.setMonth(advanced.getMonth() + 1);
  }
  return advanced.getTime();
}

/**
 * 点过「知道了」的提醒存在浏览器本地。只放内存的话，刷新页面（页面重新加载）或从别的
 * 底部 tab 切回首页（HomeView 被卸载重建）都会忘了点过，同一条提醒反复弹，老人会烦。
 *
 * <p>记的是“备忘 id + 这一轮的到点时刻”而不是光记 id：每天/每周/每月的重复提醒，
 * 下一轮到点还得再弹一次；一次性的没有下一轮，点过就永远不弹了。改了提醒时间会算出
 * 新的到点时刻，也就自然重新弹——那本来就是另一回事了。
 */
const DISMISS_STORE_KEY = 'silver-agent.dismissed-reminders';

function dismissKey(memoId: string, occurrenceAt: number): string {
  return `${memoId}@${occurrenceAt}`;
}

function readDismissed(): ReadonlySet<string> {
  try {
    const raw = window.localStorage.getItem(DISMISS_STORE_KEY);
    const rows: unknown = raw ? JSON.parse(raw) : [];
    return new Set(Array.isArray(rows) ? rows.filter((row): row is string => typeof row === 'string') : []);
  } catch {
    return new Set(); // 无痕模式下 localStorage 可能直接抛，读不到就当没点过
  }
}

function writeDismissed(keys: ReadonlySet<string>): void {
  try {
    window.localStorage.setItem(DISMISS_STORE_KEY, JSON.stringify([...keys]));
  } catch {
    // 存不进去就算了，至少这一次打开页面里点了不再弹
  }
}

export function HomeView({ onNavigate, onOpenTravel, onOpenPage }: HomeViewProps) {
  const [appointment, setAppointment] = useState<AppointmentSummary | null>(null);
  const [userName, setUserName] = useState('您好');
  // 首页只用“有提醒的”那一类：横幅和“最近到点”都从它算。提醒是当天的、攒不起来，拿全没问题。
  const [memos, setMemos] = useState<HealthMemo[]>([]);
  // 长期备忘和健康记录只留一个按钮，不在首页铺开；所以只问条数，不把内容拉回来
  // （长期备忘只增不减，为了算个数就把它整个拉下来，页面会随着它越记越多越来越慢）
  const [standingCount, setStandingCount] = useState(0);
  const [recordTotal, setRecordTotal] = useState(0);
  // null 表示“还没从浏览器里读出来”：这时干脆不渲染横幅，服务端和客户端首次渲染就一致了
  const [dismissedKeys, setDismissedKeys] = useState<ReadonlySet<string> | null>(null);
  // “现在”由定时器每 30 秒推进一次：人停在首页时，到点横幅会自己出现，不用刷新页面
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    void getAppointments().then(rows => setAppointment(rows.find(row => row.status === 'CONFIRMED') ?? null)).catch(() => setAppointment(null));
    void getUserProfile().then(user => setUserName(user.name)).catch(() => setUserName('您好'));
    reloadMemos();
    reloadRecords();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  // 放 effect 里读而不是 useState 的初始值：localStorage 只有浏览器有，服务端读不到
  useEffect(() => { setDismissedKeys(readDismissed()); }, []);

  const dismiss = (memoId: string, occurrenceAt: number) => {
    const next = new Set(dismissedKeys ?? []).add(dismissKey(memoId, occurrenceAt));
    setDismissedKeys(next);
    writeDismissed(next);
  };

  const reloadMemos = () => {
    void getMemosByKind('timed').then(setMemos).catch(() => setMemos([]));
    void getMemoCounts().then(counts => setStandingCount(counts.standing)).catch(() => setStandingCount(0));
  };

  const reloadRecords = () => {
    void getHealthRecordTotal().then(setRecordTotal).catch(() => setRecordTotal(0));
  };

  // 拿回来的本来就是“有提醒的”那一类
  const timed = memos;
  const current = new Date(now);
  // 首页只摆一条：最接近到点的那条，其余的点“全部提醒”进去看
  const nearest = timed.length === 0 ? null
    : [...timed].sort((left, right) => nearestDueAt(left, current) - nearestDueAt(right, current))[0];

  // 到点横幅：只提醒已经到点的（不提前弹），到点后 6 小时内仍提醒，取最早一条。
  // 每天/每周/每月的重复备忘按“今天的到点”算，所以到点了每天都还会弹出来。
  const banner = dismissedKeys === null ? null : (() => {
    const due = memos
      .filter(memo => memo.remindAt)
      .map(memo => ({ memo, occurrence: nextOccurrence(memo.remindAt as string, memo.repeatRule, current) }))
      .filter((item): item is { memo: HealthMemo; occurrence: Date } => item.occurrence != null)
      .map(item => ({ memo: item.memo, at: item.occurrence.getTime() }))
      .filter(item => {
        const diff = item.at - now;
        return diff <= 0 && diff > -DUE_WINDOW_MS;
      })
      .filter(item => !dismissedKeys.has(dismissKey(item.memo.id, item.at)))
      .sort((left, right) => left.at - right.at)[0];
    return due ?? null;
  })();

  return (
    <main className="space-y-6 px-5 pb-8 pt-6">
      <header className="flex items-center justify-between">
        <div><p className="text-base text-muted-foreground">下午好</p><h1 className="text-2xl font-bold tracking-tight">{userName}</h1></div>
        <button onClick={() => window.alert('这里是比赛演示的人工帮助入口，当前页面信息已经为您保留。')} aria-label="咨询人工" className="flex min-h-12 items-center gap-2 rounded-2xl border-2 border-primary/60 bg-secondary px-4 text-[17px] font-bold text-primary shadow-sm"><Headphones className="size-6" />人工帮助</button>
      </header>

      {banner && (
        <section aria-label="健康备忘提醒" className="flex items-start gap-3 rounded-3xl border border-[#e0a866] bg-[#fff4e2] p-4 shadow-sm">
          <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-primary text-white"><BellRing className="size-6" aria-hidden="true" /></span>
          <div className="min-w-0 flex-1">
            <p className="font-bold text-[#7a4a24]">健康备忘提醒</p>
            <p className="mt-1 break-words text-base leading-6 text-[#6c3d24]">{banner.memo.text}</p>
            <p className="mt-1 text-sm text-[#9a6a3c]">提醒时间：{banner.memo.repeatRule && banner.memo.remindAt ? fmtRepeat(banner.memo.remindAt, banner.memo.repeatRule) : fmtRemind(banner.memo.remindAt)}</p>
          </div>
          <button type="button" onClick={() => dismiss(banner.memo.id, banner.at)} className="flex min-h-10 shrink-0 items-center rounded-xl bg-white px-3 text-base font-bold text-[#7a4a24] shadow-sm">知道了</button>
        </section>
      )}

      <section className="overflow-hidden rounded-3xl bg-gradient-to-br from-[#c86436] to-[#de8b55] p-5 text-white shadow-lg shadow-orange-900/10">
        <div className="flex items-start gap-4">
          <div className="grid size-12 shrink-0 place-items-center rounded-2xl bg-white/18"><HeartHandshake className="size-7" /></div>
          <div><p className="text-sm text-white/80">银龄复诊助手</p><h2 className="mt-1 text-2xl font-bold leading-snug">想办理复诊，直接跟我说</h2><p className="mt-2 text-base leading-7 text-white/90">我会一步一步帮您预约、备齐材料和安排提醒。</p></div>
        </div>
        <button onClick={() => onNavigate('assistant')} className="mt-5 flex min-h-14 w-full items-center justify-center gap-3 rounded-2xl bg-white px-4 text-lg font-bold text-[#8e3e20] shadow-sm"><Mic className="size-6" />按住说话，开始办理</button>
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xl font-bold">下一次复诊</h2>
          <button onClick={() => onNavigate('tasks')} className="flex items-center text-base font-semibold text-primary">查看详情 <ChevronRight className="size-5" /></button>
        </div>
        {appointment ? (
          <button onClick={() => onNavigate(appointment.arrangedLabel ? 'assistant' : 'tasks')} className="w-full rounded-3xl border bg-card p-5 text-left shadow-sm">
            <div className="flex gap-4">
              <div className="grid min-w-20 place-items-center rounded-2xl bg-secondary px-3 py-2 text-center text-secondary-foreground">
                <strong className="text-2xl">{Number(appointment.date.split('-')[2])}</strong><span className="text-sm">{Number(appointment.date.split('-')[1])}月</span>
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-xl font-bold">{appointment.time.slice(0, 5)}</p>
                <p className="mt-1 truncate text-base">{appointment.hospital}</p>
                <p className="mt-1 text-base text-muted-foreground">{appointment.department} · {appointment.reminderStatus}</p>
              </div>
            </div>
            {appointment.arrangedLabel && (
              <p className="mt-3 rounded-xl bg-[#fff0dc] px-3 py-2 text-base font-semibold text-[#7a4a24]">由{appointment.arrangedLabel}帮您约好 · 如需改动，点这里告诉助手</p>
            )}
          </button>
        ) : (
          <button onClick={() => onNavigate('assistant')} className="w-full rounded-3xl border border-dashed border-[#dba976] bg-[#fffaf3] p-6 text-center shadow-sm">
            <CalendarCheck2 className="mx-auto size-9 text-primary" />
            <strong className="mt-3 block text-xl">暂无复诊预约</strong>
            <span className="mt-1 block text-base text-muted-foreground">点击这里，让助手帮您安排</span>
          </button>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-xl font-bold">常用服务</h2>
        <div className="grid grid-cols-2 gap-3">
          <button onClick={() => onNavigate(appointment ? 'tasks' : 'assistant')} className="rounded-3xl border bg-card p-4 text-left shadow-sm"><ClipboardCheck className="mb-3 size-8 text-primary" /><strong className="block text-lg">检查材料</strong><span className="mt-1 block text-sm text-muted-foreground">{appointment ? '查看材料清单' : '预约后生成清单'}</span></button>
          <button onClick={() => appointment ? onOpenTravel(appointment.appointmentId) : onNavigate('assistant')} className="rounded-3xl border bg-card p-4 text-left shadow-sm"><Route className="mb-3 size-8 text-primary" /><strong className="block text-lg">出行安排</strong><span className="mt-1 block text-sm text-muted-foreground">{appointment ? '地图、路线和诊室' : '预约后生成建议'}</span></button>
        </div>
      </section>

      <section aria-labelledby="memo-heading">
        <div className="mb-3 flex items-baseline justify-between">
          <h2 id="memo-heading" className="text-xl font-bold">提醒</h2>
          <span className="text-sm text-muted-foreground">{timed.length > 0 ? `${timed.length}条到点提醒` : '还没有到点提醒'}</span>
        </div>
        {nearest && (
          <ul className="space-y-3">
            <MemoCard key={nearest.id} memo={nearest} onChanged={reloadMemos} />
          </ul>
        )}
        {timed.length > 1 && (
          <button onClick={() => onOpenPage('reminders')} className="mt-3 flex min-h-14 w-full items-center justify-center gap-2 rounded-2xl border border-[#dfb98f] bg-[#fffaf3] px-4 text-base font-bold text-[#6c3d24] shadow-sm">
            查看全部提醒 {timed.length} 条 <ChevronRight className="size-5" aria-hidden="true" />
          </button>
        )}
        {timed.length === 0 && (
          <button onClick={() => onNavigate('assistant')} className="w-full rounded-3xl border border-dashed border-[#dba976] bg-[#fffaf3] p-6 text-center shadow-sm">
            <NotebookPen className="mx-auto size-9 text-primary" aria-hidden="true" />
            <strong className="mt-3 block text-xl">还没有到点提醒</strong>
            <span className="mt-2 block text-base leading-6 text-muted-foreground">想记下吃药、量血压或复诊前准备这些要到点提醒的事，去跟复诊助手说一句，例如“明早八点提醒我吃药”。</span>
            <span className="mt-3 inline-flex items-center gap-1 text-base font-bold text-primary">去跟复诊助手说 <ChevronRight className="size-5" /></span>
          </button>
        )}
      </section>

      {/* 单独一块，不塞在「提醒」标题底下：提醒删空之后不至于连长期备忘也一起变了位置 */}
      <section aria-labelledby="standing-heading">
        <h2 id="standing-heading" className="mb-3 text-xl font-bold">长期备忘</h2>
        <button onClick={() => onOpenPage('standing')} className="flex min-h-16 w-full items-center gap-3 rounded-3xl border bg-card px-4 py-3 text-left shadow-sm">
          <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><NotebookPen className="size-6" aria-hidden="true" /></span>
          <span className="min-w-0 flex-1">
            <strong className="block text-lg">查看长期备忘</strong>
            <span className="mt-0.5 block text-sm text-muted-foreground">{standingCount}条</span>
          </span>
          <ChevronRight className="size-6 shrink-0 text-primary" aria-hidden="true" />
        </button>
      </section>

      <section aria-labelledby="record-heading">
        <h2 id="record-heading" className="mb-3 text-xl font-bold">健康记录</h2>
        <button onClick={() => onOpenPage('records')} className="flex min-h-16 w-full items-center gap-3 rounded-3xl border bg-card px-4 py-3 text-left shadow-sm">
          <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><Activity className="size-6" aria-hidden="true" /></span>
          <span className="min-w-0 flex-1">
            <strong className="block text-lg">查看健康记录</strong>
            <span className="mt-0.5 block text-sm text-muted-foreground">共{recordTotal}条</span>
          </span>
          <ChevronRight className="size-6 shrink-0 text-primary" aria-hidden="true" />
        </button>
      </section>

      <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground"><CalendarCheck2 className="size-4" />当前内容均为比赛演示数据</p>
    </main>
  );
}
