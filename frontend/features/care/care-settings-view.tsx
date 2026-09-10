'use client';

import { ChevronRight, Moon, Sun, Type } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';

interface CareSettingsProps {
  onBack: () => void;
}

const items = [
  { key: 'font', title: '字体大小', description: '调整页面文字的大小', Icon: Type },
  { key: 'brightness', title: '显示亮度', description: '调节屏幕的明暗', Icon: Sun },
  { key: 'night', title: '夜间模式', description: '深色显示，夜间更柔和', Icon: Moon },
];

const comingSoon = () => window.alert('该功能将在后续版本提供，敬请期待。');

/** 我的页里的“设置”：显示偏好等预留项，仅提供入口按钮，暂不实现功能。 */
export function CareSettingsView({ onBack }: CareSettingsProps) {
  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="设置" onBack={onBack} hideHelp />

      <section className="overflow-hidden rounded-3xl border bg-card shadow-sm">
        {items.map(({ key, title, description, Icon }, index) => (
          <button
            key={key}
            type="button"
            onClick={comingSoon}
            className={`flex min-h-18 w-full items-center gap-4 px-5 text-left ${index < items.length - 1 ? 'border-b' : ''}`}
          >
            <Icon className="size-7 shrink-0 text-primary" />
            <div className="min-w-0 flex-1"><strong className="text-lg">{title}</strong><p className="text-sm text-muted-foreground">{description}</p></div>
            <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
          </button>
        ))}
      </section>
    </main>
  );
}
