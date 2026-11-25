import type { ReactNode } from 'react';

export function MobileShell({ children, largeText = false }: { children: ReactNode; largeText?: boolean }) {
  return (
    <main className={`min-h-dvh bg-background pb-28 text-foreground ${largeText ? 'large-text' : ''}`}>
      {/*
        这里必须是 overflow-x-clip 而不是 overflow-hidden。
        overflow-hidden 会把这一层变成滚动容器，而它的高度跟着内容长、自己永远不滚，
        于是里面所有页头（PageHeader 的 sticky top-0）都没有可粘的余量，跟着页面一起滚走——
        表现就是「页头固定失效」，且每个老人页都中招，不只是助手页。
        overflow-x-clip 保留横向裁切的本意（挡住超宽内容），但不产生滚动容器，sticky 恢复作用。
      */}
      <div className="mx-auto min-h-dvh w-full max-w-[480px] overflow-x-clip bg-background shadow-[0_0_48px_rgb(96_59_35/8%)]">
        {children}
      </div>
    </main>
  );
}

