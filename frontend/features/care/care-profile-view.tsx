'use client';

import { useEffect, useState } from 'react';
import { ChevronRight, Headphones, Repeat2, Settings, ShieldCheck, UserRound, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getCareElders } from '@/lib/care-api';
import type { CareElder } from '@/types/domain';

interface CareProfileProps {
  caregiverId: string;
  actorName: string;
  onSwitchActor: () => void;
  onBack: () => void;
  onOpenElders: () => void;
  onOpenSettings: () => void;
}

/** 协同照护端我的页：身份卡（含切换身份）、协同长辈入口与设置等。 */
export function CareProfileView({ caregiverId, actorName, onSwitchActor, onBack, onOpenElders, onOpenSettings }: CareProfileProps) {
  const [elders, setElders] = useState<CareElder[]>([]);

  useEffect(() => {
    void getCareElders(caregiverId).then(setElders).catch(() => setElders([]));
  }, [caregiverId]);

  const elderLine = elders.length > 0 ? elders.map(elder => elder.name).join('、') : '查看协同长辈与关系';
  const comingSoon = () => window.alert('该功能将在后续版本提供，敬请期待。');
  const helpAndFeedback = () => window.alert('这里是比赛演示的人工帮助入口，当前页面信息已经为您保留。');

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="我的" onBack={onBack} hideHelp />

      <section className="flex items-center gap-4 rounded-3xl bg-gradient-to-r from-[#f3c78f] to-[#f8dfbd] p-5">
        <div className="grid size-16 shrink-0 place-items-center rounded-full bg-white/75"><UserRound className="size-8 text-primary" /></div>
        <h2 className="min-w-0 flex-1 truncate text-2xl font-bold">{actorName}</h2>
        <button
          type="button"
          onClick={onSwitchActor}
          className="flex min-h-11 shrink-0 items-center gap-1.5 rounded-2xl bg-white/80 px-3 text-[15px] font-bold text-[#8e3e20] shadow-sm transition active:scale-95"
        >
          <Repeat2 className="size-5" aria-hidden="true" />
          切换身份
        </button>
      </section>

      <button onClick={onOpenElders} className="flex w-full items-center gap-4 rounded-3xl border bg-card p-4 text-left shadow-sm transition active:scale-[0.99]">
        <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-secondary text-primary"><UsersRound className="size-6" aria-hidden="true" /></span>
        <span className="min-w-0 flex-1">
          <strong className="block text-lg">我协同的长辈</strong>
          <span className="mt-0.5 block truncate text-base text-muted-foreground">{elderLine}</span>
        </span>
        <ChevronRight className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
      </button>

      <section className="overflow-hidden rounded-3xl border bg-card shadow-sm">
        <button onClick={onOpenSettings} className="flex min-h-18 w-full items-center gap-4 border-b px-5 text-left">
          <Settings className="size-7 shrink-0 text-primary" />
          <div className="min-w-0 flex-1"><strong className="text-lg">设置</strong><p className="text-sm text-muted-foreground">字体大小、亮度与夜间模式</p></div>
          <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
        </button>
        <button onClick={comingSoon} className="flex min-h-18 w-full items-center gap-4 border-b px-5 text-left">
          <ShieldCheck className="size-7 shrink-0 text-primary" />
          <div className="min-w-0 flex-1"><strong className="text-lg">隐私与安全</strong><p className="text-sm text-muted-foreground">查看数据使用说明</p></div>
          <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
        </button>
        <button onClick={helpAndFeedback} className="flex min-h-18 w-full items-center gap-4 px-5 text-left">
          <Headphones className="size-7 shrink-0 text-primary" />
          <div className="min-w-0 flex-1"><strong className="text-lg">帮助与反馈</strong><p className="text-sm text-muted-foreground">咨询人工或提交使用反馈</p></div>
          <ChevronRight className="size-5 shrink-0 text-muted-foreground" />
        </button>
      </section>

      <p className="px-2 text-center text-sm leading-6 text-muted-foreground">本演示使用模拟数据，不提供真实医疗建议。紧急情况请尽快联系长辈或拨打急救电话。</p>
    </main>
  );
}
