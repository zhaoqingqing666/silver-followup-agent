'use client';

import { ChevronRight, HeartHandshake, UserRound, UsersRound } from 'lucide-react';
import type { CareActor } from '@/types/domain';

interface ActorPickerProps {
  onPick: (actor: CareActor) => void;
}

const actors: Array<{ id: CareActor; title: string; description: string; icon: typeof UserRound }> = [
  { id: 'ELDER', title: '就诊人本人', description: '办理并查看自己的复诊', icon: UserRound },
  { id: 'FAMILY', title: '家属', description: '查看家人的复诊进展与通知', icon: HeartHandshake },
  { id: 'VOLUNTEER', title: '志愿者', description: '查看长辈的复诊概况', icon: UsersRound },
];

/** 进入应用时的身份选择（协同照护端与就诊人端共用入口）。 */
export function ActorPicker({ onPick }: ActorPickerProps) {
  return (
    <main className="space-y-6 px-5 pb-8 pt-6">
      <header className="pt-2">
        <p className="text-base text-muted-foreground">欢迎使用</p>
        <h1 className="mt-1 text-[26px] font-bold tracking-tight">银龄复诊协同助手</h1>
        <p className="mt-2 text-base leading-6 text-muted-foreground">请选择您的身份，页面将展示对应的服务。</p>
      </header>

      <section className="grid gap-4">
        {actors.map(({ id, title, description, icon: Icon }) => (
          <button
            key={id}
            type="button"
            onClick={() => onPick(id)}
            className="flex items-center gap-4 rounded-3xl border bg-card p-5 text-left shadow-sm transition active:scale-[0.99]"
          >
            <span className="grid size-14 shrink-0 place-items-center rounded-2xl bg-secondary text-primary">
              <Icon className="size-8" aria-hidden="true" />
            </span>
            <span className="min-w-0 flex-1">
              <strong className="block text-xl">{title}</strong>
              <span className="mt-1 block text-base text-muted-foreground">{description}</span>
            </span>
            <ChevronRight className="size-6 shrink-0 text-muted-foreground" aria-hidden="true" />
          </button>
        ))}
      </section>

      <p className="pt-2 text-center text-sm text-muted-foreground">比赛演示：身份为模拟选择，当前内容均为演示数据</p>
    </main>
  );
}
