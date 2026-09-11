'use client';

import { useEffect, useState } from 'react';
import { Inbox, LoaderCircle, MessageSquare, X } from 'lucide-react';
import { listConversations, type ConversationSummary } from '@/lib/agent-api';
import { relativeTime, statusLabel, statusTone } from './relative-time';

/** 一次列这么多条。再多老人也翻不完，真要找更早的，以后再说。 */
const PAGE_SIZE = 20;

/**
 * 历史记录浮层：最近聊过的几段对话。
 *
 * 一次只拉「标题 / 状态 / 时间」这几列，不拉会话内容——列表上不显示内容，
 * 点进去某一条再取整段。二十段对话的消息全读出来，只为了在屏幕上显示一行标题，
 * 是白花的开销。
 */
export function HistorySheet({ open, onClose, currentId, onPick, refreshToken }: {
  open: boolean;
  onClose: () => void;
  /** 当前正在看的会话，列表上标一下「当前」，免得老人点回自己 */
  currentId: string;
  onPick: (summary: ConversationSummary) => void;
  /** 每聊完一段就加一，用来在浮层打开时重新拉一次列表 */
  refreshToken: number;
}) {
  const [items, setItems] = useState<ConversationSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    // 放在 async 函数里而不是直接在 effect 体内：直接写会在渲染提交阶段同步引发一次
    // 级联渲染，而这个 setState 本来就是要等请求发出去才有意义的。
    const load = async () => {
      setLoading(true);
      setError('');
      try {
        const list = await listConversations(undefined, PAGE_SIZE);
        if (!cancelled) setItems(list);
      } catch {
        // 失败不弹窗打断，就地说明白一句
        if (!cancelled) setError('暂时打不开历史记录，请稍后再试。');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    void load();
    return () => { cancelled = true; };
  }, [open, refreshToken]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return <div className="fixed inset-0 z-50 flex flex-col justify-end">
    {/* 背景用真的按钮而不是挂 onClick 的 div：点空白处能关掉，键盘和读屏也够得着。
        面板是它的兄弟节点而不是子节点，所以点面板不会误触到背景。 */}
    <button type="button" aria-label="关闭历史记录" onClick={onClose} className="absolute inset-0 bg-black/40" />
    <section aria-label="历史记录"
      className="relative max-h-[80dvh] rounded-t-3xl bg-[#fffaf3] pb-[env(safe-area-inset-bottom)] shadow-2xl">
      <header className="flex items-center gap-3 border-b border-[#e6bc8c] px-5 py-4">
        <MessageSquare className="size-6 shrink-0 text-primary" aria-hidden="true" />
        <h2 className="flex-1 text-[21px] font-bold text-[#6c3d24]">历史记录</h2>
        <button type="button" onClick={onClose} aria-label="关闭历史记录"
          className="grid size-12 shrink-0 place-items-center rounded-full text-[#6c3d24] active:bg-[#f2e4d4]">
          <X className="size-6" aria-hidden="true" />
        </button>
      </header>

      <div className="overflow-y-auto px-4 py-3" style={{ maxHeight: 'calc(80dvh - 76px)' }}>
        {loading && <p className="flex items-center justify-center gap-2 py-6 text-base text-muted-foreground">
          <LoaderCircle className="size-5 animate-spin" aria-hidden="true" />正在读取…
        </p>}

        {!loading && error && <p className="rounded-2xl bg-[#fff0dc] px-4 py-3 text-base font-semibold text-[#a8641c]">{error}</p>}

        {!loading && !error && !items.length && <p className="flex flex-col items-center gap-2 py-8 text-base text-muted-foreground">
          <Inbox className="size-8" aria-hidden="true" />还没有聊过的记录。您说第一句话，这里就会出现。
        </p>}

        <ul className="space-y-2">
          {items.map(item => <li key={item.conversationId}>
            <button type="button" onClick={() => onPick(item)}
              className={`w-full rounded-2xl border px-4 py-3 text-left shadow-sm active:bg-[#fdf1e2] ${
                item.conversationId === currentId ? 'border-primary bg-[#fff4e2]' : 'border-[#e6bc8c] bg-white'}`}>
              <div className="flex items-start gap-2">
                <p className="min-w-0 flex-1 text-base font-bold leading-6 text-[#6c3d24]">{item.title}</p>
                <span className={`shrink-0 rounded-full px-2.5 py-0.5 text-sm font-bold ${statusTone(item.status)}`}>
                  {statusLabel(item.status)}
                </span>
              </div>
              <p className="mt-1 flex flex-wrap items-center gap-x-3 text-sm text-muted-foreground">
                <span>{relativeTime(item.updatedAt)}</span>
                <span>{item.messageCount} 条消息</span>
                {item.conversationId === currentId && <span className="font-bold text-primary">当前正在看</span>}
              </p>
            </button>
          </li>)}
        </ul>
      </div>
    </section>
  </div>;
}
