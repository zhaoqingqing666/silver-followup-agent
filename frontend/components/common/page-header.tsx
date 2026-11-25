'use client';

import type { ReactNode } from 'react';
import { ChevronLeft, Headphones } from 'lucide-react';

export function PageHeader({ title, subtitle, onBack, onHelp, hideHelp, children }: {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  onHelp?: () => void;
  /** 为 true 时不显示右侧“人工帮助”按钮。 */
  hideHelp?: boolean;
  /**
   * 贴在标题行下面的次级栏（助手页的「办理步骤 / 历史记录 / 新对话」）。
   *
   * 它和标题行同属这一块 sticky，不再自己写死 `sticky top-[76px]`：
   * 76px 是标题行的最小高度，大字模式下标题行会变高，次级栏要么被压住、要么中间留一条缝。
   * 不传 children 的页面与原来逐字相同。
   */
  children?: ReactNode;
}) {
  return (
    <header className="sticky top-0 z-50 border-b border-border/70 bg-card/95 shadow-sm backdrop-blur">
      <div className="flex min-h-[76px] items-center gap-3 px-4 py-3">
        {onBack && (
          <button
            type="button"
            aria-label="返回"
            onClick={onBack}
            className="grid size-12 shrink-0 place-items-center rounded-full text-foreground transition active:bg-muted"
          >
            <ChevronLeft className="size-7" aria-hidden="true" />
          </button>
        )}
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[23px] font-semibold">{title}</h1>
          {subtitle && <p className="mt-0.5 truncate text-[14px] text-muted-foreground">{subtitle}</p>}
        </div>
        {!hideHelp && (
          <button
            type="button"
            onClick={onHelp ?? (() => window.alert('这里是比赛演示的人工帮助入口，当前页面信息已经为您保留。'))}
            className="flex min-h-12 items-center gap-2 rounded-2xl border-2 border-primary/60 bg-secondary px-4 text-[17px] font-bold text-primary shadow-sm active:bg-accent"
          >
            <Headphones className="size-6" aria-hidden="true" />
            人工帮助
          </button>
        )}
      </div>
      {children && <div className="border-t border-border/70 px-4 py-2.5">{children}</div>}
    </header>
  );
}
