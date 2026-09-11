'use client';

import { useEffect, useState } from 'react';
import { CalendarDays, ChevronRight, History, LoaderCircle, RefreshCw, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getElderTimeline } from '@/lib/care-api';
import type { CareElder } from '@/types/domain';
import { formatDate, formatDateTime, formatHM } from './format';
import type { CareEntryKind } from './care-home-view';

const KIND_TITLES: Record<CareEntryKind, string> = {
  timeline: '就诊动态',
  appointments: '复诊预约',
  booking: '帮助预约',
  info: '长辈信息',
};

interface CareElderPickerProps {
  kind: CareEntryKind;
  caregiverId: string;
  elders: CareElder[];
  loading: boolean;
  error: string;
  onPick: (elder: CareElder) => void;
  onRetry: () => void;
  onBack: () => void;
}

type TimelinePreview =
  | { status: 'loading' }
  | { status: 'ok'; title: string; at: string; count: number }
  | { status: 'empty' }
  | { status: 'error' };

/** 协同长辈选择：仅当一位照护者绑定多位长辈时出现；卡片按功能预览内容。 */
export function CareElderPickerView({ kind, caregiverId, elders, loading, error, onPick, onRetry, onBack }: CareElderPickerProps) {
  const [previews, setPreviews] = useState<Record<string, TimelinePreview>>({});
  const isTimeline = kind === 'timeline';
  const isBooking = kind === 'booking';
  const isInfo = kind === 'info';

  useEffect(() => {
    if (!isTimeline || loading || error || elders.length === 0) return;
    let cancelled = false;
    void (async () => {
      const next: Record<string, TimelinePreview> = {};
      for (const elder of elders) {
        try {
          const events = await getElderTimeline(caregiverId, elder.elderId);
          next[elder.elderId] = events.length > 0
            ? { status: 'ok', title: events[0].title, at: events[0].at, count: events.length }
            : { status: 'empty' };
        } catch {
          next[elder.elderId] = { status: 'error' };
        }
        if (cancelled) return;
      }
      if (!cancelled) setPreviews(next);
    })();
    return () => { cancelled = true; };
    // 仅在长辈列表或加载态变化时重建预览
  }, [isTimeline, loading, error, elders, caregiverId]);

  const viewLabel = isBooking ? '去代约' : isInfo ? '查看资料' : isTimeline ? '查看动态' : '查看预约';

  const preview = (elderId: string): TimelinePreview => previews[elderId] ?? { status: 'loading' };

  const renderTimeline = (elder: CareElder) => {
    const state = preview(elder.elderId);
    if (state.status === 'ok') {
      return (
        <div className="mt-4 flex items-start gap-3 rounded-2xl bg-[#fff0dc] p-4 ring-1 ring-[#efcda4]">
          <History className="mt-0.5 size-6 shrink-0 text-primary" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-lg font-bold">{state.title}</p>
            <p className="mt-1 text-sm text-muted-foreground">共 {state.count} 条动态 · {formatDateTime(state.at)}</p>
          </div>
        </div>
      );
    }
    if (state.status === 'empty') {
      return (
        <div className="mt-4 flex items-center gap-3 rounded-2xl border border-dashed border-[#dba976] bg-[#fffaf3] p-4">
          <History className="size-6 shrink-0 text-primary" />
          <p className="text-base text-muted-foreground">暂无就诊动态</p>
        </div>
      );
    }
    if (state.status === 'error') {
      return (
        <div className="mt-4 flex items-center gap-3 rounded-2xl border border-dashed border-[#dba976] bg-[#fffaf3] p-4">
          <p className="text-base text-muted-foreground">动态暂时无法读取</p>
        </div>
      );
    }
    return (
      <div className="mt-4 flex items-center gap-3 rounded-2xl bg-[#fff0dc] p-4 ring-1 ring-[#efcda4]">
        <LoaderCircle className="size-5 animate-spin text-primary" />
        <p className="text-base text-muted-foreground">正在读取动态…</p>
      </div>
    );
  };

  const renderAppointment = (elder: CareElder) => {
    if (!elder.latestAppointment) {
      return (
        <div className="mt-4 flex items-center gap-3 rounded-2xl border border-dashed border-[#dba976] bg-[#fffaf3] p-4">
          <CalendarDays className="size-6 shrink-0 text-primary" />
          <p className="text-base text-muted-foreground">暂无复诊预约</p>
        </div>
      );
    }
    const appt = elder.latestAppointment;
    return (
      <div className="mt-4 flex gap-4 rounded-2xl bg-[#fff0dc] p-4 ring-1 ring-[#efcda4]">
        <div className="grid min-w-16 place-items-center rounded-xl bg-secondary px-2 py-1.5 text-center text-secondary-foreground">
          <strong className="text-xl">{Number(appt.date.split('-')[2])}</strong>
          <span className="text-sm">{Number(appt.date.split('-')[1])}月</span>
        </div>
        <div className="min-w-0 flex-1">
          <p className="text-lg font-bold">{formatHM(appt.time)} {formatDate(appt.date)} 复诊</p>
          <p className="mt-1 truncate text-base">{appt.hospital} · {appt.department}</p>
          {(appt.departureAt || appt.transport) && (
            <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              {appt.departureAt && <span>建议 {formatHM(appt.departureAt)} 出发</span>}
              {appt.transport && <span>{appt.transport}</span>}
            </p>
          )}
        </div>
      </div>
    );
  };

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title={KIND_TITLES[kind]} onBack={onBack} hideHelp />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取长辈…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={onRetry} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && elders.length === 0 && (
        <section className="rounded-3xl border border-[#efcda4] bg-[#fff8ed] px-6 py-10 text-center shadow-sm">
          <span className="mx-auto grid size-20 place-items-center rounded-full bg-[#ffe2bf] text-primary"><UsersRound className="size-10" /></span>
          <h2 className="mt-5 text-2xl font-bold">暂无协同的长辈</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">绑定的家属或志愿者关系会显示在这里。</p>
        </section>
      )}

      {!loading && !error && elders.length > 0 && (
        <section className="grid gap-4">
          {elders.map(elder => (
            <button
              key={elder.elderId}
              type="button"
              onClick={() => onPick(elder)}
              className="rounded-3xl border bg-card p-5 text-left shadow-sm transition active:scale-[0.99]"
            >
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h2 className="text-xl font-bold">{elder.name}</h2>
                  {!isBooking && <p className="mt-0.5 text-sm text-muted-foreground">{elder.relationship ?? '协同的就诊人'}</p>}
                </div>
                <span className="flex items-center gap-1 text-base font-semibold text-primary">{viewLabel} <ChevronRight className="size-5" /></span>
              </div>

              {isTimeline ? renderTimeline(elder) : (isBooking || isInfo) ? null : renderAppointment(elder)}
            </button>
          ))}
        </section>
      )}
    </main>
  );
}
