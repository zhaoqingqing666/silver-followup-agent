'use client';

import { useEffect, useRef } from 'react';

/**
 * 列表底部：滚到这里就自动拉下一页。
 *
 * <p>自动加载之外还留一个能点的按钮——滚动触发是无声的，老人不一定知道“下面还有”，
 * 也可能习惯自己点一下才放心。真到了底就给一句“没有更早的了”，免得一直往下扒。
 */
export function LoadMore({ hasMore, loading, onLoadMore, endText }: {
  hasMore: boolean;
  loading: boolean;
  onLoadMore: () => void;
  /** 已经拉到头时显示的文案。 */
  endText: string;
}) {
  const nodeRef = useRef<HTMLDivElement | null>(null);
  // 调用处每次渲染都给新的箭头函数，放依赖里会让 observer 反复重建，用 ref 拿最新的
  const moreRef = useRef(onLoadMore);
  useEffect(() => { moreRef.current = onLoadMore; }, [onLoadMore]);

  useEffect(() => {
    const node = nodeRef.current;
    if (!node || !hasMore || loading) return;
    // 环境不支持就先算了：下面还有那个按钮可以点
    if (typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) moreRef.current();
    }, { rootMargin: '240px' }); // 提前一点拉，别等真滚到底看见空白
    observer.observe(node);
    return () => observer.disconnect();
  }, [hasMore, loading]);

  return (
    <div ref={nodeRef} className="mt-4 pb-2">
      {loading && <p className="text-center text-base text-muted-foreground">正在加载</p>}
      {!loading && hasMore && (
        <button type="button" onClick={onLoadMore}
                className="flex min-h-14 w-full items-center justify-center rounded-2xl border border-[#dfb98f] bg-[#fffaf3] px-4 text-base font-bold text-[#6c3d24] shadow-sm">
          看更早的
        </button>
      )}
      {!loading && !hasMore && <p className="text-center text-base text-muted-foreground">{endText}</p>}
    </div>
  );
}
