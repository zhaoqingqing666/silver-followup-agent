'use client';

import { useEffect, useState } from 'react';
import { ChevronRight, LoaderCircle, RefreshCw, UserRound, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getCareElders } from '@/lib/care-api';
import type { CareElder } from '@/types/domain';

interface CareMyEldersProps {
  caregiverId: string;
  /** 点一位长辈看他的「长辈信息」；没有的话这份名单就是个死胡同，看完没有下一步。 */
  onOpen: (elder: CareElder) => void;
  onBack: () => void;
}

/** 我的页里“我协同的长辈”：展示与该照护者绑定的长辈及其关系，可点进长辈信息。 */
export function CareMyEldersView({ caregiverId, onOpen, onBack }: CareMyEldersProps) {
  const [elders, setElders] = useState<CareElder[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setElders(await getCareElders(caregiverId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取协同的长辈');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [caregiverId]);

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="我协同的长辈" onBack={onBack} hideHelp />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取长辈…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && elders.length === 0 && (
        <section className="rounded-3xl border border-[#efcda4] bg-[#fff8ed] px-6 py-10 text-center shadow-sm">
          <span className="mx-auto grid size-20 place-items-center rounded-full bg-[#ffe2bf] text-primary"><UsersRound className="size-10" /></span>
          <h2 className="mt-5 text-2xl font-bold">暂无协同的长辈</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">绑定的家属或志愿者关系会显示在这里。</p>
        </section>
      )}

      {!loading && !error && elders.length > 0 && (
        <>
          <p className="px-1 text-base text-muted-foreground">共 {elders.length} 位长辈。</p>
          <section className="grid gap-3">
            {elders.map(elder => (
              <button
                key={elder.elderId}
                type="button"
                onClick={() => onOpen(elder)}
                className="flex w-full items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]"
              >
                <span className="grid size-12 shrink-0 place-items-center rounded-full bg-secondary text-primary"><UserRound className="size-6" /></span>
                <span className="min-w-0 flex-1">
                  <strong className="block text-lg font-bold">{elder.name}</strong>
                  <span className="mt-0.5 block truncate text-base text-muted-foreground">与您的关系：{elder.relationship ?? '协同的就诊人'}</span>
                </span>
                <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            ))}
          </section>
        </>
      )}
    </main>
  );
}
