import { ChevronLeft, Headphones } from 'lucide-react';

export function PageHeader({ title, subtitle, onBack }: { title: string; subtitle?: string; onBack?: () => void }) {
  return (
    <header className="flex min-h-[76px] items-center gap-3 border-b border-border/70 bg-card/70 px-4 py-3 backdrop-blur">
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
      <button
        type="button"
        className="flex min-h-11 items-center gap-1 rounded-2xl px-3 text-[15px] font-medium text-primary active:bg-accent"
      >
        <Headphones className="size-5" aria-hidden="true" />
        人工帮助
      </button>
    </header>
  );
}

