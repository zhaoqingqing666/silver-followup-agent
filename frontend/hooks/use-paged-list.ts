'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

export interface PagedList<T> {
  /** 已经拿到的全部记录（各页拼在一起），顺序就是后端给的顺序。 */
  rows: T[];
  /** 第一页是否已经回来：用来区分“还在转圈”和“真的没有”。 */
  loaded: boolean;
  /** 后面还有没有更早的。 */
  hasMore: boolean;
  loading: boolean;
  loadMore: () => void;
  /** 增删改之后重新拉：拉回“已经翻到的那么多条”，不把列表缩回第一页。 */
  reload: () => void;
}

/**
 * 一页页往回翻的列表：先取第一页，往下翻到底再取下一页。
 *
 * <p>长期备忘和健康记录都只增不减，攒多了既不能一次性全拉下来，也不能只留最近的
 * N 条——那样更早的就永远看不到了。分页能一路翻到头。
 *
 * @param fetchPage (offset, limit) => Promise<T[]> 取从第 offset 条开始的 limit 条。
 * @param pageSize  一页几条。
 */
export function usePagedList<T>(fetchPage: (offset: number, limit: number) => Promise<T[]>,
                                pageSize: number): PagedList<T> {
  const [rows, setRows] = useState<T[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  // 取数函数每次渲染都是新的（调用处写的是箭头函数），放依赖里会让 effect 反复重跑，
  // 所以用 ref 拿最新的那份，effect 就只跟 pageSize 走。
  const fetchRef = useRef(fetchPage);
  // 同一时刻只放一页在路上：往下滚会连着触发好几次，不然会重复拉同一页
  const busyRef = useRef(false);

  useEffect(() => { fetchRef.current = fetchPage; }, [fetchPage]);

  const load = useCallback(async (offset: number, limit: number) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setLoading(true);
    try {
      const batch = await fetchRef.current(offset, limit);
      setRows(previous => (offset === 0 ? batch : [...previous, ...batch]));
      // 拿回来是满的一页，就当作后面还有；下次翻到底会拉回空的，那时再收尾
      setHasMore(batch.length >= pageSize);
    } catch {
      // 拉不到就当作到底了：宁可让老人看到“没有更早的了”，也别让它一直转圈
      setHasMore(false);
    } finally {
      busyRef.current = false;
      setLoading(false);
      setLoaded(true);
    }
  }, [pageSize]);

  useEffect(() => { void load(0, pageSize); }, [load, pageSize]);

  // 改过或删过之后重新拉：要的是“已经翻到的那几页”，不是缩回第一页——
  // 从很下面删掉一条，列表整个跳回顶部会让人以为点错了。
  const reload = useCallback(() => {
    void load(0, Math.max(pageSize, rows.length));
  }, [load, pageSize, rows.length]);

  return { rows, loaded, hasMore, loading, loadMore: () => { void load(rows.length, pageSize); }, reload };
}
