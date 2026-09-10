'use client';

import { useEffect, useState } from 'react';
import { CalendarDays, CalendarPlus, ChevronRight, HeartHandshake, History, LoaderCircle, RefreshCw, Route, TriangleAlert, UserRound } from 'lucide-react';
import { getCareElders } from '@/lib/care-api';
import type { CareAttention, CareElder } from '@/types/domain';
import { formatDate, formatHM } from './format';

export type CareEntryKind = 'timeline' | 'appointments' | 'booking' | 'info';

interface CareHomeProps {
  caregiverId: string;
  onOpen: (kind: CareEntryKind) => void;
  /** 首页直达某位长辈的某一页（跳过选人）。 */
  onOpenElder: (kind: CareEntryKind, elder: CareElder) => void;
}

/** 本地时区的今天（yyyy-MM-dd），用于判断预约是否还在未来。 */
const todayLocal = (): string => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
};

/** 这张快照是否是在进行的复诊（进行中且未过期）。 */
const isUpcoming = (appt: NonNullable<CareElder['latestAppointment']>): boolean =>
  appt.status === 'CONFIRMED' && appt.date >= todayLocal();

/** 长辈那一行的预约摘要文案。 */
const elderLine = (elder: CareElder): string => {
  const appt = elder.latestAppointment;
  if (!appt) return '暂无复诊预约';
  if (appt.status === 'CANCELLED') return `最近一次复诊已取消（${formatDate(appt.date)}）`;
  if (isUpcoming(appt)) return `${formatDate(appt.date)} ${formatHM(appt.time)} · ${appt.hospital} ${appt.department}`;
  return `最近一次复诊 ${formatDate(appt.date)} · ${appt.hospital} ${appt.department}`;
};

/** 协同照护端首页：长辈服务入口 + 需要关注的动态 + 我协同的长辈总览。 */
export function CareHomeView({ caregiverId, onOpen, onOpenElder }: CareHomeProps) {
  const [elders, setElders] = useState<CareElder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setElders(await getCareElders(caregiverId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取协同的长辈');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [caregiverId]);

  /** 需要优先关注：有请求协助 / 紧急暂停动态的长辈。 */
  const attention = elders
    .filter((elder): elder is CareElder & { attention: CareAttention } => elder.attention != null)
    .sort((a, b) => (a.attention.at < b.attention.at ? 1 : -1));
  const topAttention = attention[0] ?? null;

  /** 有当前照护者确认陪同且还在未来进行的复诊（只展示自己参与的）。 */
  const accompanies = elders
    .filter(elder => elder.latestAppointment && elder.latestAppointment.accompaniedBy === caregiverId && isUpcoming(elder.latestAppointment))
    .sort((a, b) => (a.latestAppointment!.date > b.latestAppointment!.date ? 1 : -1));

  const initials = (name: string) => name.trim().charAt(0) || '长';

  return (
    <main className="space-y-6 px-5 pb-8 pt-6">
      {topAttention && (
        <section className="rounded-3xl border border-[#f0c0b5] bg-[#fff1ee] p-4 shadow-sm ring-1 ring-red-200">
          <button type="button" onClick={() => onOpenElder('timeline', topAttention)} className="flex w-full items-start gap-3 text-left">
            <span className="grid size-10 shrink-0 place-items-center rounded-2xl bg-red-50 text-[#a1452f]"><TriangleAlert className="size-6" aria-hidden="true" /></span>
            <span className="min-w-0 flex-1">
              <strong className="block text-lg text-[#a1452f]">{topAttention.attention.title}</strong>
              <span className="mt-0.5 block text-base leading-6 text-muted-foreground">
                {topAttention.name}需要您关注，点此查看最近动态。
              </span>
            </span>
            <ChevronRight className="mt-2 size-5 shrink-0 text-[#a1452f]" />
          </button>
        </section>
      )}

      <section className="overflow-hidden rounded-3xl bg-gradient-to-br from-[#c86436] to-[#de8b55] p-5 text-white shadow-lg shadow-orange-900/10">
        <div className="flex items-start gap-4">
          <div className="grid size-12 shrink-0 place-items-center rounded-2xl bg-white/18"><HeartHandshake className="size-7" /></div>
          <div>
            <p className="text-sm text-white/80">银龄复诊协同助手</p>
            <h2 className="mt-1 text-2xl font-bold leading-snug">一起帮长辈把复诊安排好</h2>
            <p className="mt-2 text-base leading-7 text-white/90">就诊动态、复诊预约和消息通知，都能在这里查看。</p>
          </div>
        </div>
      </section>

      <section>
        <h2 className="mb-3 text-xl font-bold">长辈服务</h2>
        <div className="grid gap-3">
          <button type="button" onClick={() => onOpen('booking')} className="flex items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
            <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><CalendarPlus className="size-6" aria-hidden="true" /></span>
            <span className="min-w-0 flex-1">
              <strong className="block text-lg">帮助预约</strong>
              <span className="mt-0.5 block text-base text-muted-foreground">帮长辈预约，进展同步到长辈的助手</span>
            </span>
            <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
          </button>

          <button type="button" onClick={() => onOpen('timeline')} className="flex items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
            <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><History className="size-6" aria-hidden="true" /></span>
            <span className="min-w-0 flex-1">
              <strong className="block text-lg">就诊动态</strong>
              <span className="mt-0.5 block text-base text-muted-foreground">查看长辈最近的复诊进展与经过</span>
            </span>
            <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
          </button>

          <button type="button" onClick={() => onOpen('appointments')} className="flex items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
            <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><CalendarDays className="size-6" aria-hidden="true" /></span>
            <span className="min-w-0 flex-1">
              <strong className="block text-lg">复诊预约</strong>
              <span className="mt-0.5 block text-base text-muted-foreground">查看长辈的预约卡片与提醒</span>
            </span>
            <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
          </button>

          <button type="button" onClick={() => onOpen('info')} className="flex items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
            <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><UserRound className="size-6" aria-hidden="true" /></span>
            <span className="min-w-0 flex-1">
              <strong className="block text-lg">长辈信息</strong>
              <span className="mt-0.5 block text-base text-muted-foreground">查看资料与联系人</span>
            </span>
            <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
          </button>
        </div>
      </section>

      {accompanies.length > 0 && (
        <section>
          <h2 className="mb-3 text-xl font-bold">接下来有您陪同的复诊</h2>
          <div className="grid gap-3">
            {accompanies.map(elder => {
              const appt = elder.latestAppointment!;
              return (
                <button key={elder.elderId} type="button" onClick={() => onOpenElder('booking', elder)} className="rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
                  <div className="flex items-center justify-between gap-3">
                    <strong className="text-lg">{elder.name}的复诊</strong>
                    <span className="flex items-center gap-1 text-base font-semibold text-primary">查看安排<ChevronRight className="size-5" /></span>
                  </div>
                  <p className="mt-1 text-base text-muted-foreground">{formatDate(appt.date)} {formatHM(appt.time)} · {appt.hospital} {appt.department}</p>
                  {appt.departureAt && (
                    <p className="mt-1 flex items-center gap-1.5 text-sm text-muted-foreground"><Route className="size-4 text-primary" />建议 {formatHM(appt.departureAt)} 出发，可点此查看或调整这次安排</p>
                  )}
                </button>
              );
            })}
          </div>
        </section>
      )}

      <section>
        <h2 className="mb-3 text-xl font-bold">我协同的长辈</h2>
        {loading && (
          <section className="grid min-h-28 place-items-center rounded-3xl border bg-card">
            <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-7 animate-spin" /><p className="mt-2 text-base">正在读取长辈…</p></div>
          </section>
        )}
        {!loading && error && (
          <section className="rounded-3xl border bg-card p-5 text-center">
            <p className="text-lg">{error}</p>
            <button onClick={() => void load()} className="mt-3 inline-flex min-h-11 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
          </section>
        )}
        {!loading && !error && elders.length === 0 && (
          <section className="rounded-3xl border border-[#efcda4] bg-[#fff8ed] px-5 py-8 text-center shadow-sm">
            <p className="text-lg text-muted-foreground">暂无协同的长辈，绑定关系后这里会显示。</p>
          </section>
        )}
        {!loading && !error && elders.length > 0 && (
          <div className="grid gap-3">
            {elders.map(elder => (
              <button key={elder.elderId} type="button" onClick={() => onOpenElder('appointments', elder)} className="flex items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
                <span className="grid size-12 shrink-0 place-items-center rounded-full bg-secondary text-lg font-bold text-secondary-foreground">{initials(elder.name)}</span>
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-2">
                    <strong className="text-lg">{elder.name}</strong>
                    {elder.relationship && <span className="rounded-full bg-muted px-2 py-0.5 text-sm text-muted-foreground">{elder.relationship}</span>}
                    {elder.attention && <span className="rounded-full bg-red-50 px-2 py-0.5 text-sm font-bold text-[#a1452f]">需关注</span>}
                  </span>
                  <span className="mt-0.5 block truncate text-base text-muted-foreground">{elderLine(elder)}</span>
                </span>
                <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            ))}
          </div>
        )}
      </section>

      <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground">当前内容均为比赛演示数据</p>
    </main>
  );
}
