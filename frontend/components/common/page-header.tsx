'use client';

import { ChevronLeft, Headphones } from 'lucide-react';

export function PageHeader({ title, subtitle, onBack, onHelp, hideHelp }: {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  onHelp?: () => void;
  /** 为 true 时不显示右侧“人工帮助”按钮。 */
  hideHelp?: boolean;
}) {
  return (
    <header className="sticky top-0 z-50 flex min-h-[76px] items-center gap-3 border-b border-border/70 bg-card/95 px-4 py-3 shadow-sm backdrop-blur">
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
    </header>
  );
}
