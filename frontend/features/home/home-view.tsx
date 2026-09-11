'use client';

import { useEffect, useState } from 'react';
import { CalendarCheck2, ChevronRight, ClipboardCheck, Headphones, HeartHandshake, Mic, Route } from 'lucide-react';
import { getAppointments } from '@/lib/appointment-api';
import type { AppointmentSummary, TabId } from '@/types/domain';

interface HomeViewProps { onNavigate: (tab: TabId) => void }

export function HomeView({ onNavigate }: HomeViewProps) {
  const [appointment, setAppointment] = useState<AppointmentSummary | null>(null);

  useEffect(() => {
    void getAppointments().then(rows => setAppointment(rows.find(row => row.status === 'CONFIRMED') ?? null)).catch(() => setAppointment(null));
  }, []);

  return (
    <main className="space-y-6 px-5 pb-8 pt-6">
      <header className="flex items-center justify-between">
        <div><p className="text-base text-muted-foreground">下午好</p><h1 className="text-2xl font-bold tracking-tight">王阿姨</h1></div>
        <button aria-label="咨询人工" className="flex min-h-12 items-center gap-2 rounded-2xl border bg-card px-3 font-semibold text-primary shadow-sm"><Headphones className="size-5" />人工帮助</button>
      </header>

      <section className="overflow-hidden rounded-3xl bg-gradient-to-br from-[#c86436] to-[#de8b55] p-5 text-white shadow-lg shadow-orange-900/10">
        <div className="flex items-start gap-4">
          <div className="grid size-12 shrink-0 place-items-center rounded-2xl bg-white/18"><HeartHandshake className="size-7" /></div>
          <div><p className="text-sm text-white/80">银龄复诊助手</p><h2 className="mt-1 text-2xl font-bold leading-snug">想办理复诊，直接跟我说</h2><p className="mt-2 text-base leading-7 text-white/90">我会一步一步帮您预约、备齐材料和安排提醒。</p></div>
        </div>
        <button onClick={() => onNavigate('assistant')} className="mt-5 flex min-h-14 w-full items-center justify-center gap-3 rounded-2xl bg-white px-4 text-lg font-bold text-[#8e3e20] shadow-sm"><Mic className="size-6" />按住说话，开始办理</button>
      </section>

      <section>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-xl font-bold">下一次复诊</h2>
          <button onClick={() => onNavigate('tasks')} className="flex items-center text-base font-semibold text-primary">查看详情 <ChevronRight className="size-5" /></button>
        </div>
        {appointment ? (
          <button onClick={() => onNavigate('tasks')} className="w-full rounded-3xl border bg-card p-5 text-left shadow-sm">
            <div className="flex gap-4">
              <div className="grid min-w-20 place-items-center rounded-2xl bg-secondary px-3 py-2 text-center text-secondary-foreground">
                <strong className="text-2xl">{Number(appointment.date.split('-')[2])}</strong><span className="text-sm">{Number(appointment.date.split('-')[1])}月</span>
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-xl font-bold">{appointment.time.slice(0, 5)}</p>
                <p className="mt-1 truncate text-base">{appointment.hospital}</p>
                <p className="mt-1 text-base text-muted-foreground">{appointment.department} · {appointment.reminderStatus}</p>
              </div>
            </div>
          </button>
        ) : (
          <button onClick={() => onNavigate('assistant')} className="w-full rounded-3xl border border-dashed border-[#dba976] bg-[#fffaf3] p-6 text-center shadow-sm">
            <CalendarCheck2 className="mx-auto size-9 text-primary" />
            <strong className="mt-3 block text-xl">暂无复诊预约</strong>
            <span className="mt-1 block text-base text-muted-foreground">点击这里，让助手帮您安排</span>
          </button>
        )}
      </section>

      <section>
        <h2 className="mb-3 text-xl font-bold">常用服务</h2>
        <div className="grid grid-cols-2 gap-3">
          <button onClick={() => onNavigate(appointment ? 'tasks' : 'assistant')} className="rounded-3xl border bg-card p-4 text-left shadow-sm"><ClipboardCheck className="mb-3 size-8 text-primary" /><strong className="block text-lg">检查材料</strong><span className="mt-1 block text-sm text-muted-foreground">{appointment ? '查看材料清单' : '预约后生成清单'}</span></button>
          <button onClick={() => onNavigate(appointment ? 'tasks' : 'assistant')} className="rounded-3xl border bg-card p-4 text-left shadow-sm"><Route className="mb-3 size-8 text-primary" /><strong className="block text-lg">出行安排</strong><span className="mt-1 block text-sm text-muted-foreground">{appointment ? '查看出发时间' : '预约后生成建议'}</span></button>
        </div>
      </section>
      <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground"><CalendarCheck2 className="size-4" />当前内容均为比赛演示数据</p>
    </main>
  );
}
