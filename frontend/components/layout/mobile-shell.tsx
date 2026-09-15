import type { ReactNode } from 'react';

export function MobileShell({ children, largeText = false }: { children: ReactNode; largeText?: boolean }) {
  return (
    <main className={`min-h-dvh bg-background pb-28 text-foreground ${largeText ? 'large-text' : ''}`}>
      {/* 这里必须是 overflow-x-clip 而不是 overflow-hidden。
          overflow-hidden 会把这一层变成滚动容器，而它的高度是随内容撑开的、自己永远不滚，
          于是页面里所有 position:sticky 的吸顶层（标题栏、状态行、操作按钮区）都会失效、
          跟着内容一起滑走。改成 clip 只裁横向溢出、不建立滚动容器，吸顶才真正生效。 */}
      <div className="mx-auto min-h-dvh w-full max-w-[480px] overflow-x-clip bg-background shadow-[0_0_48px_rgb(96_59_35/8%)]">
        {children}
      </div>
    </main>
  );
}

