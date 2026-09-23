'use client';

import { useEffect, useState } from 'react';
import { BellRing, ChevronRight, Inbox, LoaderCircle, RefreshCw } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getCareNotifications } from '@/lib/care-api';
import type { CareNotification } from '@/types/domain';
import { formatDateTime, TONE_DOT_COLORS, TONE_TEXT_COLORS } from './format';

interface CareInboxProps {
  caregiverId: string;
  /**
   * 点一条消息进这位长辈的就诊动态。消息里只有 id 和名字，下钻要的长辈对象得现查，
   * 所以这里是个可能失败的异步动作——失败由本页显示出来，不能悄悄什么也不做。
   */
  onOpenElder: (elderId: string) => Promise<void>;
  onBack: () => void;
}

/** 协同照护端消息页：按长辈聚合的通知；点一条进这位长辈的就诊动态。 */
export function CareInboxView({ caregiverId, onOpenElder, onBack }: CareInboxProps) {
  const [notifications, setNotifications] = useState<CareNotification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  // 点一条消息下钻失败，和“整页读不出来”不是一回事：前者只是这一次点击没成。
  // 两者共用一个 error 的话，点一条长辈已经解绑的消息会把整个收件箱换成错误页，
  // 家属得点一次「重新读取」才能看回列表——所以单独存一个。
  const [clickError, setClickError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    setClickError('');
    try {
      setNotifications(await getCareNotifications(caregiverId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取消息');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [caregiverId]);

  /** 点一条消息进那位长辈的动态。查不到人（关系被解绑了）就在本页说清楚，别默默不动。 */
  const openElder = async (elderId: string) => {
    setClickError('');
    try {
      await onOpenElder(elderId);
    } catch (cause) {
      setClickError(cause instanceof Error ? cause.message : '无法打开这位长辈的就诊动态');
    }
  };

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
          <p className="mt-2 text-lg leading-8 text-muted-foreground">长辈复诊的进展与安排会以消息形式显示在这里。点一条可以看这位长辈的就诊动态。</p>
        </section>
      )}

      {!loading && !error && clickError && (
        <p role="alert" className="rounded-2xl bg-[#fdf0ed] px-3 py-2 text-base font-bold text-[#b3452f]">{clickError}</p>
      )}

      {!loading && !error && notifications.length > 0 && (
        <section className="grid gap-3">
          {notifications.map(item => (
            <button
              key={item.notificationId}
              type="button"
              onClick={() => void openElder(item.elderId)}
              className="flex w-full items-start gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]"
            >
              <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><BellRing className="size-6" /></span>
              <span className="min-w-0 flex-1">
                <span className="flex items-center justify-between gap-3">
                  <span className="flex min-w-0 items-center gap-2">
                    {/* 只有要人注意的才带点：紧急/求助、取消这一类，别的条目不用抢眼 */}
                    {(item.tone === 'danger' || item.tone === 'warning') && (
                      <span className={`size-2.5 shrink-0 rounded-full ${TONE_DOT_COLORS[item.tone]}`} aria-hidden="true" />
                    )}
                    <strong className={`truncate text-lg ${TONE_TEXT_COLORS[item.tone]}`}>{item.elderName}</strong>
                  </span>
                  <time className="shrink-0 text-sm text-muted-foreground">{formatDateTime(item.sentAt)}</time>
                </span>
                <span className="mt-1 block text-base leading-6 text-muted-foreground">{item.content}</span>
                <span className="mt-1 flex items-center gap-1 text-sm font-bold text-primary">
                  查看这位长辈的就诊动态<ChevronRight className="size-4" aria-hidden="true" />
                </span>
              </span>
            </button>
          ))}
        </section>
      )}
    </main>
  );
}
