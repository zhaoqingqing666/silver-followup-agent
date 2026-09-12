'use client';

import { useEffect, useState } from 'react';
import { CalendarDays, ChevronRight, History, Home, LoaderCircle, Phone, RefreshCw, UserRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getUserProfile } from '@/lib/appointment-api';
import type { CareElder, UserProfile } from '@/types/domain';

interface CareElderInfoProps {
  elder: CareElder;
  onBack: () => void;
  onOpenTimeline: () => void;
  onOpenAppointments: () => void;
}

/** 照护者只读查看某位长辈的资料与联系人（不提供编辑）。 */
export function CareElderInfoView({ elder, onBack, onOpenTimeline, onOpenAppointments }: CareElderInfoProps) {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setProfile(await getUserProfile(elder.elderId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取长辈资料');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [elder.elderId]);

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="长辈信息" subtitle={`您协同的${elder.relationship ?? ''}${elder.name}`} onBack={onBack} hideHelp />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取资料…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && profile && (
        <>
          <section className="flex items-center gap-4 rounded-3xl border bg-card p-5 shadow-sm">
            <span className="grid size-16 shrink-0 place-items-center rounded-full bg-secondary text-2xl font-bold text-secondary-foreground">{profile.name.trim().charAt(0) || '长'}</span>
            <div className="min-w-0 flex-1">
              <h2 className="text-2xl font-bold">{profile.name}</h2>
              <p className="mt-0.5 text-base text-muted-foreground">与您的关系：{elder.relationship ?? '协同的就诊人'}</p>
            </div>
          </section>

          <section className="rounded-3xl border bg-card p-5 shadow-sm">
            <h3 className="flex items-center gap-2 text-lg font-bold"><UserRound className="size-5 text-primary" />基本资料</h3>
            <div className="mt-3 grid gap-3 text-base">
              <p className="flex items-center gap-2"><Home className="size-4 shrink-0 text-primary" /><span className="text-muted-foreground">常住地址</span><span className="ml-auto max-w-[60%] truncate">{profile.homeAddress ?? '未填写'}</span></p>
              <p className="flex items-center gap-2"><CalendarDays className="size-4 shrink-0 text-primary" /><span className="text-muted-foreground">常用出行</span><span className="ml-auto max-w-[60%] truncate">{profile.preferredTransport ?? '未填写'}</span></p>
            </div>
          </section>

          <section className="rounded-3xl border bg-card p-5 shadow-sm">
            <h3 className="flex items-center gap-2 text-lg font-bold"><Phone className="size-5 text-primary" />家属联系人</h3>
            {(profile.contacts ?? []).length === 0 ? (
              <p className="mt-3 text-base text-muted-foreground">暂无登记的家属联系人。</p>
            ) : (
              <ul className="mt-2 divide-y">
                {(profile.contacts ?? []).map(contact => (
                  <li key={contact.id} className="flex min-h-12 items-center gap-3 py-2 text-lg">
                    <span>{contact.name}</span>
                    <span className="text-sm text-muted-foreground">（{contact.relationship}）</span>
                    <span className="ml-auto text-sm text-muted-foreground">{contact.maskedPhone}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <div className="grid gap-3">
            <button type="button" onClick={onOpenAppointments} className="flex min-h-14 items-center gap-3 rounded-2xl border bg-card px-5 text-left text-lg font-bold shadow-sm active:scale-[0.99]">
              <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-secondary text-primary"><CalendarDays className="size-5" /></span>
              <span className="min-w-0 flex-1">查看复诊预约</span>
              <ChevronRight className="size-5 text-muted-foreground" />
            </button>
            <button type="button" onClick={onOpenTimeline} className="flex min-h-14 items-center gap-3 rounded-2xl border bg-card px-5 text-left text-lg font-bold shadow-sm active:scale-[0.99]">
              <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-secondary text-primary"><History className="size-5" /></span>
              <span className="min-w-0 flex-1">查看就诊动态</span>
              <ChevronRight className="size-5 text-muted-foreground" />
            </button>
          </div>
        </>
      )}
    </main>
  );
}
