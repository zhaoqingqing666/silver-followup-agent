import type { ReactNode } from 'react';

export function MobileShell({ children, largeText = false }: { children: ReactNode; largeText?: boolean }) {
  return (
    <main className={`min-h-dvh bg-background pb-28 text-foreground ${largeText ? 'large-text' : ''}`}>
      <div className="mx-auto min-h-dvh w-full max-w-[480px] overflow-hidden bg-background shadow-[0_0_48px_rgb(96_59_35/8%)]">
        {children}
      </div>
    </main>
  );
}

