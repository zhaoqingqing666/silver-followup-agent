'use client';

import { NotebookPen } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { LoadMore } from '@/components/common/load-more';
import { getMemosByKind } from '@/lib/memo-api';
import { groupByMonth } from '@/lib/month-group';
import { usePagedList } from '@/hooks/use-paged-list';
import { MemoCard } from '@/features/records/memo-card';
import type { HealthMemo } from '@/types/domain';

/** 提醒页 / 长期备忘页的文案；两页只有筛选条件和话术不一样，卡片是同一种。 */
const COPY = {
  reminders: {
    title: '提醒',
    subtitle: '到点会提醒您的事',
    emptyTitle: '还没有到点提醒',
    emptyHint: '想记下吃药、量血压或复诊前准备这些要到点提醒的事，去跟复诊助手说一句，例如“明早八点提醒我吃药”。',
  },
  standing: {
    title: '长期备忘',
    subtitle: '不提醒、只记下的事',
    emptyTitle: '还没有长期备忘',
    emptyHint: '想记下不提醒的事，比如药盒放哪、复查要带什么，去跟复诊助手说一句就行。',
  },
} as const;

/** 长期备忘一页几条。它只增不减，攒多了全靠分页一路往回翻。 */
const STANDING_PAGE_SIZE = 30;

/**
 * 备忘列表页：提醒（有时间的）和长期备忘（没时间的）共用。
 * 一页只放一类，所以哪一类变多都不会把另一类顶到看不见的地方。
 *
 * <p>两类的活法不一样，取数也就不一样：**提醒是当天的、又要删，攒不起来**，一次拿全；
 * **长期备忘只增不减**，得按月分段、一页页往回翻。
 */
export function MemoListView({ page, onBack, onGoAssistant }: {
  page: 'reminders' | 'standing';
  onBack: () => void;
  onGoAssistant: () => void;
}) {
  const copy = COPY[page];
  const { rows: fetched, loaded, hasMore, loading, loadMore, reload } = usePagedList<HealthMemo>(
      (offset, limit) => (page === 'reminders'
          ? getMemosByKind('timed')          // 一次拿全，不分页
          : getMemosByKind('standing', limit, offset)),
      STANDING_PAGE_SIZE);

  // 提醒按时间从近到远（“今天要吃的药”排最前）；长期备忘按月分段，段内就是后端给的“最近记的在最前”
  const rows = page === 'reminders'
      ? [...fetched].sort((left, right) => (left.remindAt as string).localeCompare(right.remindAt as string))
      : fetched;
  const groups = page === 'standing' ? groupByMonth(rows, memo => memo.createdAt) : null;

  return (
    <main className="pb-10">
      <PageHeader title={copy.title} subtitle={copy.subtitle} onBack={onBack} />

      <div className="px-5 pt-5">
        <div className="mb-3 flex items-baseline justify-between">
          <h2 className="text-xl font-bold">{copy.title}</h2>
          <span className="text-sm text-muted-foreground">{rows.length > 0 ? `共${rows.length}条` : copy.subtitle}</span>
        </div>
        {loaded && rows.length === 0 ? (
          <button onClick={onGoAssistant} className="w-full rounded-3xl border border-dashed border-[#dba976] bg-[#fffaf3] p-6 text-center shadow-sm">
            <NotebookPen className="mx-auto size-9 text-primary" aria-hidden="true" />
            <strong className="mt-3 block text-xl">{copy.emptyTitle}</strong>
            <span className="mt-2 block text-base leading-6 text-muted-foreground">{copy.emptyHint}</span>
          </button>
        ) : groups ? (
          <>
            {groups.map(group => (
              <section key={group.key} className="mb-5">
                <h3 className="mb-2 text-base font-bold text-muted-foreground">{group.title}</h3>
                <ul className="space-y-3">
                  {group.rows.map(memo => <MemoCard key={memo.id} memo={memo} onChanged={reload} />)}
                </ul>
              </section>
            ))}
            <LoadMore hasMore={hasMore} loading={loading} onLoadMore={loadMore} endText="没有更早的了" />
          </>
        ) : (
          <ul className="space-y-3">
            {rows.map(memo => <MemoCard key={memo.id} memo={memo} onChanged={reload} />)}
          </ul>
        )}
      </div>
    </main>
  );
}
