'use client';

import { useEffect, useState } from 'react';
import { Activity, Send } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { LoadMore } from '@/components/common/load-more';
import {
  getFamilyContact, getHealthRecordTotal, getHealthRecords, sendHealthReport, RECORD_ITEMS,
  type HealthReportRange,
} from '@/lib/health-record-api';
import { groupByDay, groupByMonth, timeOfDay } from '@/lib/month-group';
import { usePagedList } from '@/hooks/use-paged-list';
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
 * 健康记录页：助手帮老人记下的实测数值，按测量时间倒序、先按项目筛、再按月分段、段内按天收拢。
 *
 * <p>顶上那排项目按钮是主入口：老人问的是“我血压最近怎么样”，不是“9月10日我记了什么”，
 * 所以先按项目分最顺手。分页、计数、“发给家属”都跟着筛选走，看到的和发出去的是一批数。
 *
 * <p>段内按天收拢是为了“一天量了好几次”别占满几屏：日期提到小标题里，卡片只剩钟点和数值。
 */
export function HealthRecordListView({ onBack, onGoAssistant }: {
  onBack: () => void;
  onGoAssistant: () => void;
}) {
  const [item, setItem] = useState<string | null>(null);
  const [contact, setContact] = useState<FamilyContact | null>(null);
  const [contactLoaded, setContactLoaded] = useState(false);
  // 点“发给家属”之后才问发多长时间——两个按钮一直摆着会让人以为得先选一个
  const [choosing, setChoosing] = useState(false);
  const [sending, setSending] = useState(false);
  const [outcome, setOutcome] = useState<SendOutcome | null>(null);

  useEffect(() => {
    let alive = true;
    void getFamilyContact().catch(() => null).then(row => {
      if (!alive) return;
      setContact(row);
      setContactLoaded(true);
    });
    return () => { alive = false; };
  }, []);

  // 筛着哪个项目，发出去的就只有哪个项目：按钮上得写明，不然老人以为点的还是“全发”
  const sendLabel = item ? `把${item}发给${contact ? contact.relationship : '家属'}` : '发给家属';
  const sendHint = !contactLoaded
      ? '把记录汇总成一段话发过去'
      : !contact
          ? '还没有配置家属联系人'
          : item
              ? `${contact.relationship} ${contact.name}只会收到${item}这一段`
              : `${contact.relationship} ${contact.name}会收到一段汇总`;

  const send = (range: HealthReportRange) => {
    if (sending) return;   // 手抖点两下别发两条
    setSending(true);
    setOutcome(null);
    void sendHealthReport(range, item)
        .then(result => {
          setChoosing(false);
          const what = item ? item + '记录' : '健康记录';
          setOutcome(result.sent
              ? { ok: true, title: `已把${what}发给${result.contactLabel}（${result.rangeLabel}）`, detail: result.message }
              : { ok: false, title: result.reason ?? '这次没有发出去。', detail: null });
        })
        .catch(() => {
          setChoosing(false);
          setOutcome({ ok: false, title: '这次没有发出去，请稍后再试。', detail: null });
        })
        .finally(() => setSending(false));
  };

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
              <strong id="send-report-heading" className="block text-lg">{sendLabel}</strong>
              <span className="mt-0.5 block text-sm text-muted-foreground">{sendHint}</span>
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
              {sending ? '正在发送' : sendLabel}
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

        {/* 换筛选就把上次的发送结果收掉：留着的话老人切到“血糖”还看着“已把血压记录发给…” */}
        <ItemFilter value={item} onChange={next => { setItem(next); setOutcome(null); setChoosing(false); }} />
        {/* key 换掉就把列表整个重来一遍：不这么做的话，翻到第 5 页再切筛选，
            新的第一页会接在旧的那 250 条后面，看起来像筛选没生效 */}
        <RecordList key={item ?? 'all'} item={item} onGoAssistant={onGoAssistant} />
      </div>
    </main>
  );
}

/** 顶上那排项目按钮。老人最常问的是某一个指标，所以先按项目分，比按月分更顺手。 */
function ItemFilter({ value, onChange }: {
  value: string | null;
  onChange: (item: string | null) => void;
}) {
  const options: Array<{ key: string; label: string; item: string | null }> = [
    { key: 'all', label: '全部', item: null },
    ...RECORD_ITEMS.map(name => ({ key: name, label: name, item: name as string | null })),
  ];
  return (
    <fieldset className="mb-3 flex flex-wrap gap-2">
      <legend className="sr-only">按项目筛选</legend>
      {options.map(option => {
        const active = option.item === value;
        return (
          <button key={option.key} type="button" onClick={() => onChange(option.item)}
                  aria-pressed={active}
                  className={`min-h-11 rounded-full border px-4 text-base font-bold shadow-sm ${
                      active ? 'border-primary bg-primary text-white' : 'border-[#dfb98f] bg-[#fffaf3] text-[#6c3d24]'}`}>
            {option.label}
          </button>
        );
      })}
    </fieldset>
  );
}

/**
 * 记录列表本体：取数、计数、分段都跟着 item 走。
 *
 * <p>单独拆出来是为了让外面能用 `key` 换掉它——筛选一变就换一个实例，
 * 分页状态（已经翻到第几页、已经拿到几条）跟着归零，不然会把上一个筛选的记录留在下面。
 */
function RecordList({ item, onGoAssistant }: {
  item: string | null;
  onGoAssistant: () => void;
}) {
  const { rows, loaded, error, hasMore, loading, loadMore, reload } = usePagedList<HealthRecord>(
      (offset, limit) => getHealthRecords(limit, offset, item), RECORD_PAGE_SIZE);
  // 条数单独问：分页之后“这一页有几条”已经不等于“一共几条”了
  const [total, setTotal] = useState(0);

  useEffect(() => {
    let alive = true;
    void getHealthRecordTotal(item).catch(() => 0).then(count => { if (alive) setTotal(count); });
    return () => { alive = false; };
  }, [item]);

  // 先按月、段内再按天：月段回答“这是哪阵子的”，天段回答“这一天量了几回”
  const months = groupByMonth(rows, record => record.recordedAt);

  return (
    <>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="text-xl font-bold">{item ?? '健康记录'}</h2>
        <span className="text-sm text-muted-foreground">{total > 0 ? `共${total}条` : '按时间倒序'}</span>
      </div>
      {/* 读不到 ≠ 一条都没有：后端没起或网络断了的时候，下面那张空态大卡会理直气壮地
          说「还没有血压记录，跟助手说一句…」，老人就会以为自己的记录丢了 */}
      {loaded && error && (
        <section className="mb-3 rounded-3xl border bg-card p-6 text-center shadow-sm">
          <p className="text-lg">{error}</p>
          <button type="button" onClick={reload}
                  className="mt-4 inline-flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white">
            重新读取
          </button>
        </section>
      )}
      {loaded && !error && (rows.length === 0 ? (
        <button onClick={onGoAssistant} className="w-full rounded-3xl border border-dashed border-[#dba976] bg-[#fffaf3] p-6 text-center shadow-sm">
          <Activity className="mx-auto size-9 text-primary" aria-hidden="true" />
          <strong className="mt-3 block text-xl">还没有{item ?? '健康'}记录</strong>
          <span className="mt-2 block text-base leading-6 text-muted-foreground">
            跟复诊助手说一句“我的{item ?? '血压'}是100”，我就帮您记下来。
          </span>
        </button>
      ) : (
        <>
          {months.map(month => (
            <section key={month.key} className="mb-5">
              <h3 className="mb-2 text-base font-bold text-muted-foreground">{month.title}</h3>
              {groupByDay(month.rows, record => record.recordedAt).map(day => (
                <div key={day.key} className="mb-4">
                  <h4 className="mb-2 text-sm font-bold text-muted-foreground">{day.title}</h4>
                  <ul className="space-y-2">
                    {day.rows.map(record => (
                      <li key={record.id} className="flex items-baseline gap-3 rounded-2xl border bg-card px-4 py-3 shadow-sm">
                        <span className="w-12 shrink-0 text-sm text-muted-foreground">{timeOfDay(record.recordedAt)}</span>
                        {/* 筛了具体项目时标题已经写着项目名了，卡片里再写一遍是噪音 */}
                        {!item && <strong className="shrink-0 text-[17px]">{record.item}</strong>}
                        <span className="ml-auto text-right text-[17px]">
                          {record.valueText}{record.unit ? ` ${record.unit}` : ''}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              ))}
            </section>
          ))}
          <LoadMore hasMore={hasMore} loading={loading} onLoadMore={loadMore} endText="没有更早的了" />
        </>
      ))}
    </>
  );
}
