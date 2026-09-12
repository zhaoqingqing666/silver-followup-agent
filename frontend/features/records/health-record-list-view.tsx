'use client';

import { useEffect, useState } from 'react';
import { Activity, Send } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { LoadMore } from '@/components/common/load-more';
import {
  getFamilyContact, getHealthRecordTotal, getHealthRecords, sendHealthReport,
  type HealthReportRange,
} from '@/lib/health-record-api';
import { groupByMonth } from '@/lib/month-group';
import { usePagedList } from '@/hooks/use-paged-list';
import { fmtRemind } from '@/features/records/memo-card';
import type { FamilyContact, HealthRecord } from '@/types/domain';

/** 一次拉多少条。健康记录只增不减，一页页往回翻，不设“最多看多少条”的顶。 */
export const RECORD_PAGE_SIZE = 50;

/** 发完之后给老人的回话：发了什么、或者为什么没发出去。 */
interface SendOutcome {
  ok: boolean;
  title: string;
  detail: string | null;
}

/**
 * 健康记录页：助手帮老人记下的实测数值，按测量时间倒序、按月分段。
 *
 * <p>按月分段是为了“找之前的数”：一天量好几次，平铺着排几百条就没法翻了；
 * 有了「2026年9月」这样的段落标题，老人滚到那一段就知道是哪个月。
 *
 * <p>顶上还有一件事：把这段记录汇总了发给家属。页面这个按钮和助手里那句
 * “把这个月的血压发给女儿”走的是后端同一段汇总代码，不是两份说法。
 */
export function HealthRecordListView({ onBack, onGoAssistant }: {
  onBack: () => void;
  onGoAssistant: () => void;
}) {
  const { rows, loaded, hasMore, loading, loadMore } = usePagedList<HealthRecord>(
      (offset, limit) => getHealthRecords(limit, offset), RECORD_PAGE_SIZE);
  // 条数单独问：分页之后“这一页有几条”已经不等于“一共几条”了
  const [total, setTotal] = useState(0);
  const [contact, setContact] = useState<FamilyContact | null>(null);
  const [contactLoaded, setContactLoaded] = useState(false);
  // 点“发给家属”之后才问发多长时间——两个按钮一直摆着会让人以为得先选一个
  const [choosing, setChoosing] = useState(false);
  const [sending, setSending] = useState(false);
  const [outcome, setOutcome] = useState<SendOutcome | null>(null);

  useEffect(() => {
    let alive = true;
    void getHealthRecordTotal().catch(() => 0).then(count => { if (alive) setTotal(count); });
    void getFamilyContact().catch(() => null).then(row => {
      if (!alive) return;
      setContact(row);
      setContactLoaded(true);
    });
    return () => { alive = false; };
  }, []);

  const send = (range: HealthReportRange) => {
    if (sending) return;   // 手抖点两下别发两条
    setSending(true);
    setOutcome(null);
    void sendHealthReport(range)
        .then(result => {
          setChoosing(false);
          setOutcome(result.sent
              ? { ok: true, title: `已发给${result.contactLabel}（${result.rangeLabel}）`, detail: result.message }
              : { ok: false, title: result.reason ?? '这次没有发出去。', detail: null });
        })
        .catch(() => {
          setChoosing(false);
          setOutcome({ ok: false, title: '这次没有发出去，请稍后再试。', detail: null });
        })
        .finally(() => setSending(false));
  };

  const groups = groupByMonth(rows, record => record.recordedAt);

  return (
    <main className="pb-10">
      <PageHeader title="健康记录" subtitle="助手帮您记下的实测数值" onBack={onBack} />

      <div className="px-5 pt-5">
        <section aria-labelledby="send-report-heading" className="mb-4 rounded-3xl border bg-card p-4 shadow-sm">
          <div className="flex items-start gap-3">
            <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-secondary text-primary">
              <Send className="size-6" aria-hidden="true" />
            </span>
            <div className="min-w-0 flex-1">
              <strong id="send-report-heading" className="block text-lg">发给家属</strong>
              <span className="mt-0.5 block text-sm text-muted-foreground">
                {contactLoaded
                    ? contact ? `${contact.relationship} ${contact.name}会收到一段汇总` : '还没有配置家属联系人'
                    : '把记录汇总成一段话发过去'}
              </span>
            </div>
          </div>
          {choosing ? (
            <div className="mt-3 grid grid-cols-2 gap-3">
              <button type="button" onClick={() => send('week')} disabled={sending}
                      className="flex min-h-14 items-center justify-center rounded-2xl border border-[#dfb98f] bg-[#fffaf3] px-4 text-base font-bold text-[#6c3d24] shadow-sm disabled:opacity-60">
                最近一周
              </button>
              <button type="button" onClick={() => send('month')} disabled={sending}
                      className="flex min-h-14 items-center justify-center rounded-2xl border border-[#dfb98f] bg-[#fffaf3] px-4 text-base font-bold text-[#6c3d24] shadow-sm disabled:opacity-60">
                最近一个月
              </button>
            </div>
          ) : (
            <button type="button" onClick={() => setChoosing(true)} disabled={sending || !contact}
                    className="mt-3 flex min-h-14 w-full items-center justify-center rounded-2xl bg-primary px-4 text-lg font-bold text-white shadow-sm disabled:opacity-60">
              {sending ? '正在发送' : '发给家属'}
            </button>
          )}
          {outcome && (
            <div className={`mt-3 rounded-2xl px-3 py-3 text-base leading-6 ${
                outcome.ok ? 'bg-[#eef7ec] text-[#33603a]' : 'bg-[#fff4e2] text-[#7a4a24]'}`}>
              <p className="font-bold">{outcome.title}</p>
              {outcome.detail && <p className="mt-1 break-words">{outcome.detail}</p>}
            </div>
          )}
        </section>

        <div className="mb-3 flex items-baseline justify-between gap-3">
          <h2 className="text-xl font-bold">健康记录</h2>
          <span className="text-sm text-muted-foreground">{total > 0 ? `共${total}条` : '按时间倒序'}</span>
        </div>
        {loaded && rows.length === 0 ? (
          <button onClick={onGoAssistant} className="w-full rounded-3xl border border-dashed border-[#dba976] bg-[#fffaf3] p-6 text-center shadow-sm">
            <Activity className="mx-auto size-9 text-primary" aria-hidden="true" />
            <strong className="mt-3 block text-xl">还没有健康记录</strong>
            <span className="mt-2 block text-base leading-6 text-muted-foreground">跟复诊助手说一句“我的血压是100”，我就帮您记下来。</span>
          </button>
        ) : (
          <>
            {groups.map(group => (
              <section key={group.key} className="mb-5">
                <h3 className="mb-2 text-base font-bold text-muted-foreground">{group.title}</h3>
                <ul className="space-y-3">
                  {group.rows.map(record => (
                    <li key={record.id} className="rounded-3xl border bg-card p-4 shadow-sm">
                      <div className="flex items-baseline justify-between gap-3">
                        <strong className="text-[17px]">{record.item}</strong>
                        <span className="text-sm text-muted-foreground">{fmtRemind(record.recordedAt)}</span>
                      </div>
                      <p className="mt-1 text-base">{record.valueText}{record.unit ? ` ${record.unit}` : ''}</p>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
            <LoadMore hasMore={hasMore} loading={loading} onLoadMore={loadMore} endText="没有更早的了" />
          </>
        )}
      </div>
    </main>
  );
}
