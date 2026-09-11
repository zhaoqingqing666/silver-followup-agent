'use client';

import { useEffect, useState } from 'react';
import { Accessibility, Brain, ChevronRight, Headphones, ShieldCheck, Trash2, UserRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { Switch } from '@/components/ui/switch';
import { forgetMemory, listMemories, type AgentMemory } from '@/lib/agent-api';
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
  /** null 表示还没读回来。用 null 而不是另开一个 loading 布尔，少一个可能对不上的状态。 */
  const [memories, setMemories] = useState<AgentMemory[] | null>(null);
  const [memoriesError, setMemoriesError] = useState('');
  const [reloadToken, setReloadToken] = useState(0);
  /** 正等着第二下确认的那一条。忘掉是不可逆的，老人手抖一下不该就没了。 */
  const [confirmingKey, setConfirmingKey] = useState('');
  const [forgettingKey, setForgettingKey] = useState('');

  useEffect(() => {
    void getUserProfile().then(setUser).catch(() => setUser(null));
  }, []);

  useEffect(() => {
    // 只在响应回来之后才写 state；卸载或重新读取时用 cancelled 丢弃上一轮的迟到结果，
    // 否则「重新读取」连点两下，先发的那次可能后到，把新结果盖回旧的。
    let cancelled = false;
    listMemories().then(
      rows => {
        if (cancelled) return;
        setMemories(rows);
        setMemoriesError('');
      },
      cause => {
        if (cancelled) return;
        setMemories([]);
        setMemoriesError(cause instanceof Error ? cause.message : '暂时读不到助手记住的事');
      });
    return () => { cancelled = true; };
  }, [reloadToken]);

  const reloadMemories = () => {
    setMemories(null);
    setMemoriesError('');
    setReloadToken(token => token + 1);
  };

  const forget = async (key: string) => {
    setForgettingKey(key);
    setMemoriesError('');
    try {
      await forgetMemory(key);
      setMemories(list => (list ?? []).filter(item => item.key !== key));
      setConfirmingKey('');
    } catch (cause) {
      setMemoriesError(cause instanceof Error ? cause.message : '暂时没能忘掉这条');
    } finally {
      setForgettingKey('');
    }
  };

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

    {/* 助手记住的事摆在明面上：记了什么、为什么记、怎么删，三件事都要老人自己看得见。
        「忘掉」必须做成本页唯一的入口——记忆只在确认放行的预约落库时才写，
        那么清除它的权力也只能交回到人手里，不能由模型或别的页面代劳。 */}
    <section className="overflow-hidden rounded-3xl border bg-card shadow-sm">
      <div className="flex min-h-18 items-center gap-4 border-b px-5">
        <Brain className="size-7 text-primary" />
        <div className="flex-1">
          <strong className="text-lg">助手记住的事</strong>
          <p className="text-sm text-muted-foreground">都是您确认过的复诊里留下来的，下次可以少说一遍</p>
        </div>
      </div>

      {memories === null && <p className="px-5 py-6 text-center text-base text-muted-foreground">正在读取…</p>}

      {memories !== null && memoriesError && (
        <div className="px-5 py-6 text-center">
          <p className="text-base text-red-700">{memoriesError}</p>
          <button onClick={reloadMemories} className="mt-3 min-h-12 rounded-2xl bg-primary px-5 font-bold text-white">重新读取</button>
        </div>
      )}

      {memories !== null && !memoriesError && memories.length === 0 && (
        <p className="px-5 py-6 text-base leading-8 text-muted-foreground">助手还没记住什么。等您办成一次复诊预约，它会记下常去的医院和科室。</p>
      )}

      {memories !== null && !memoriesError && memories.length > 0 && (
        <ul>
          {memories.map(item => (
            <li key={item.key} className="border-b px-5 py-4 last:border-b-0">
              <p className="text-lg leading-8">{item.content}</p>
              {confirmingKey === item.key ? (
                <div className="mt-3">
                  <p className="text-base text-muted-foreground">忘掉以后，助手下次就不会再提它了。</p>
                  <div className="mt-2 flex gap-3">
                    <button onClick={() => void forget(item.key)} disabled={forgettingKey === item.key}
                      className="min-h-12 flex-1 rounded-2xl bg-[#c2564a] px-4 font-bold text-white disabled:opacity-50">
                      {forgettingKey === item.key ? '正在忘…' : '确定忘掉'}
                    </button>
                    <button onClick={() => setConfirmingKey('')} disabled={forgettingKey === item.key}
                      className="min-h-12 flex-1 rounded-2xl border bg-white px-4 font-bold disabled:opacity-50">再想想</button>
                  </div>
                </div>
              ) : (
                <button onClick={() => setConfirmingKey(item.key)}
                  className="mt-3 flex min-h-12 items-center gap-2 rounded-2xl border px-4 text-base font-bold text-muted-foreground">
                  <Trash2 className="size-5" />忘掉这条
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      <p className="border-t bg-[#fffaf3] px-5 py-3 text-sm leading-6 text-muted-foreground">助手只是拿它们少问您一句。真要办什么，还是您点过「确认」才算数。</p>
    </section>
    <p className="px-2 text-center text-sm leading-6 text-muted-foreground">本项目仅使用模拟数据，不提供诊断和用药建议。紧急情况请及时联系家属或拨打急救电话。</p>
  </main>;
}
