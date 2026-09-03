import { CalendarDays, Home, MessageCircleHeart, UserRound } from 'lucide-react';

import type { TabId } from '@/types/domain';

const navItems: Array<{ id: TabId; label: string; icon: typeof Home }> = [
  { id: 'home', label: '首页', icon: Home },
  { id: 'tasks', label: '事项', icon: CalendarDays },
  { id: 'assistant', label: '助手', icon: MessageCircleHeart },
  { id: 'profile', label: '我的', icon: UserRound },
];

export function BottomNav({ activeTab, onChange }: { activeTab: TabId; onChange: (tab: TabId) => void }) {
  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-30 mx-auto flex h-[82px] w-full max-w-[480px] items-start justify-around border-t border-border bg-card/96 px-2 pt-2 backdrop-blur"
      aria-label="主要导航"
    >
      {navItems.map(({ id, label, icon: Icon }) => {
        const active = id === activeTab;
        return (
          <button
            key={id}
            type="button"
            className={`flex min-h-16 min-w-16 flex-col items-center justify-center gap-1 rounded-2xl px-3 text-[14px] font-medium transition active:scale-95 ${active ? 'text-primary' : 'text-muted-foreground'}`}
            aria-current={active ? 'page' : undefined}
            onClick={() => onChange(id)}
          >
            <Icon className="size-6" aria-hidden="true" />
            {label}
          </button>
        );
      })}
    </nav>
  );
}

