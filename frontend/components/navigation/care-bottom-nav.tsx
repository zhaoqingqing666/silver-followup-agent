import { BellRing, Home, MessageCircleHeart, UserRound } from 'lucide-react';

export type CareTabId = 'home' | 'messages' | 'assistant' | 'profile';

const navItems: Array<{ id: CareTabId; label: string; icon: typeof Home }> = [
  { id: 'home', label: '首页', icon: Home },
  { id: 'messages', label: '消息', icon: BellRing },
  { id: 'assistant', label: '助手', icon: MessageCircleHeart },
  { id: 'profile', label: '我的', icon: UserRound },
];

/** 协同照护端底部导航，样式与就诊人端 BottomNav 一致，仅导航项不同。 */
export function CareBottomNav({ activeTab, onChange }: { activeTab: CareTabId; onChange: (tab: CareTabId) => void }) {
  return (
    <nav
      className="fixed inset-x-0 bottom-0 z-30 mx-auto flex h-[82px] w-full max-w-[480px] items-start justify-around border-t border-border bg-card/96 px-2 pt-2 backdrop-blur"
      aria-label="协同照护导航"
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
