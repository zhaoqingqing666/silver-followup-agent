'use client';

import { useEffect, useState } from 'react';
import { History, LoaderCircle, RefreshCw } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getElderTimeline } from '@/lib/care-api';
import type { CareElder, CareTimelineEvent } from '@/types/domain';
import { formatDateTime } from './format';

interface ElderTimelineProps {
  caregiverId: string;
  elder: CareElder;
  onBack: () => void;
}

const DOT_COLORS: Record<CareTimelineEvent['tone'], string> = {
  info: 'bg-primary',
  success: 'bg-green-500',
  warning: 'bg-amber-400',
  danger: 'bg-red-500',
};

const TITLE_COLORS: Record<CareTimelineEvent['tone'], string> = {
  info: 'text-foreground',
  success: 'text-green-800',
  warning: 'text-amber-800',
  danger: 'text-red-700',
};

/** 某位长辈的就诊动态时间线。 */
export function ElderTimelineView({ caregiverId, elder, onBack }: ElderTimelineProps) {
  const [events, setEvents] = useState<CareTimelineEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setEvents(await getElderTimeline(caregiverId, elder.elderId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取就诊动态');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [caregiverId, elder.elderId]);

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="就诊动态" onBack={onBack} hideHelp />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取就诊动态…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && events.length === 0 && (
        <section className="rounded-3xl border bg-card px-6 py-10 text-center shadow-sm">
          <History className="mx-auto size-10 text-muted-foreground" />
          <h2 className="mt-4 text-xl font-bold">暂无就诊动态</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">就诊人完成一次复诊办理后，进展会按时间显示在这里。</p>
        </section>
      )}

      {!loading && !error && events.length > 0 && (
        <section className="rounded-3xl border bg-card p-4 shadow-sm">
          <ol className="grid gap-3">
            {events.map((event, index) => (
              <li key={`${event.at}-${index}`} className="flex gap-3 rounded-2xl border p-4">
                <span className="mt-1.5 grid size-3 shrink-0 place-items-center">
                  <span className={`size-3 rounded-full ${DOT_COLORS[event.tone]}`} aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="flex items-start justify-between gap-3">
                    <strong className={`text-lg leading-6 ${TITLE_COLORS[event.tone]}`}>{event.title}</strong>
                    <time className="shrink-0 pt-0.5 text-sm text-muted-foreground">{formatDateTime(event.at)}</time>
                  </div>
                  {event.detail && <p className="mt-1 text-base leading-6 text-muted-foreground">{event.detail}</p>}
                </div>
              </li>
            ))}
          </ol>
        </section>
      )}
    </main>
  );
}
