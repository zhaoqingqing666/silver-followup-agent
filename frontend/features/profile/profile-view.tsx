'use client';

import { useEffect, useState } from 'react';
import { Accessibility, ChevronRight, Headphones, ShieldCheck, UserRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { Switch } from '@/components/ui/switch';
import { getUserProfile } from '@/lib/appointment-api';
import type { TabId, UserProfile } from '@/types/domain';

interface ProfileProps {
  onNavigate: (tab: TabId) => void;
  largeText: boolean;
  onLargeTextChange: (value: boolean) => void;
  autoSpeakEnabled: boolean;
  voicePreferenceBusy: boolean;
  voicePreferenceError: string;
  onAutoSpeakChange: (value: boolean) => void;
}
export function ProfileView({ onNavigate, largeText, onLargeTextChange, autoSpeakEnabled,
  voicePreferenceBusy, voicePreferenceError, onAutoSpeakChange }: ProfileProps) {
  const [user, setUser] = useState<UserProfile | null>(null);

  useEffect(() => {
    void getUserProfile().then(setUser).catch(() => setUser(null));
  }, []);

  const familyLine = user?.contacts && user.contacts.length > 0
    ? user.contacts.map(c => `${c.relationship} ${c.name} · ${c.maskedPhone}`).join('、')
    : '暂无家属联系人';
  const displayName = user?.name || '我的';
  return <main className="space-y-5 px-5 pb-8 pt-5">
    <PageHeader title="我的" onBack={() => onNavigate('home')} />
    <section className="flex items-center gap-4 rounded-3xl bg-gradient-to-r from-[#f3c78f] to-[#f8dfbd] p-5"><div className="grid size-16 place-items-center rounded-full bg-white/75"><UserRound className="size-8 text-primary" /></div><div><h2 className="text-2xl font-bold">{displayName}</h2><p className="text-base text-muted-foreground">家属联系人：{familyLine}</p></div></section>
    <section className="overflow-hidden rounded-3xl border bg-card shadow-sm">
      <div className="flex min-h-18 items-center gap-4 border-b px-5"><Accessibility className="size-7 text-primary"/><div className="flex-1"><strong className="text-lg">特大字体</strong><p className="text-sm text-muted-foreground">让页面文字更醒目</p></div><Switch checked={largeText} onCheckedChange={onLargeTextChange} aria-label="特大字体" /></div>
      <div className="flex min-h-18 items-center gap-4 border-b px-5"><Headphones className="size-7 text-primary"/><div className="flex-1"><strong className="text-lg">语音朗读</strong><p className="text-sm text-muted-foreground">{voicePreferenceBusy ? '正在保存设置…' : '智能体新回复后自动朗读'}</p>{voicePreferenceError && <p className="text-sm text-red-700">{voicePreferenceError}</p>}</div><Switch checked={autoSpeakEnabled} disabled={voicePreferenceBusy} onCheckedChange={onAutoSpeakChange} aria-label="语音朗读" /></div>
      <button className="flex min-h-18 w-full items-center gap-4 px-5 text-left"><ShieldCheck className="size-7 text-primary"/><div className="flex-1"><strong className="text-lg">隐私与安全</strong><p className="text-sm text-muted-foreground">查看数据使用说明</p></div><ChevronRight /></button>
    </section>
    <p className="px-2 text-center text-sm leading-6 text-muted-foreground">本项目仅使用模拟数据，不提供诊断和用药建议。紧急情况请及时联系家属或拨打急救电话。</p>
  </main>;
}
