'use client';

import { useEffect, useState } from 'react';
import { BellRing, Inbox, LoaderCircle, RefreshCw } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getCareNotifications } from '@/lib/care-api';
import type { CareNotification } from '@/types/domain';
import { formatDateTime } from './format';

interface CareInboxProps {
  caregiverId: string;
  onBack: () => void;
}

/** 协同照护端消息页：按长辈聚合的通知，纯展示。 */
export function CareInboxView({ caregiverId, onBack }: CareInboxProps) {
  const [notifications, setNotifications] = useState<CareNotification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setNotifications(await getCareNotifications(caregiverId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取消息');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [caregiverId]);

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="消息" onBack={onBack} hideHelp />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取消息…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && notifications.length === 0 && (
        <section className="rounded-3xl border bg-card px-6 py-10 text-center shadow-sm">
          <Inbox className="mx-auto size-10 text-muted-foreground" />
          <h2 className="mt-4 text-xl font-bold">还没有消息</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">长辈复诊的进展与安排会以消息形式显示在这里。</p>
        </section>
      )}

      {!loading && !error && notifications.length > 0 && (
        <section className="grid gap-3">
          {notifications.map(item => (
            <div key={item.notificationId} className="flex items-start gap-4 rounded-3xl border bg-card p-4 shadow-sm">
              <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><BellRing className="size-6" /></span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center justify-between gap-3">
                  <strong className="text-lg">{item.elderName}</strong>
                  <time className="shrink-0 text-sm text-muted-foreground">{formatDateTime(item.sentAt)}</time>
                </div>
                <p className="mt-1 text-base leading-6 text-muted-foreground">{item.content}</p>
              </div>
            </div>
          ))}
        </section>
      )}
    </main>
  );
}
