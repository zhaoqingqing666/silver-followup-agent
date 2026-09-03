'use client';

import { useEffect, useState } from 'react';
import { BellRing, CalendarPlus, Check, Circle, Clock3, History, LoaderCircle, MapPin, Navigation, RefreshCw, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getAppointments } from '@/lib/appointment-api';
import type { AppointmentSummary, MaterialItem, TabId } from '@/types/domain';

export function TasksView({ onNavigate }: { onNavigate: (tab: TabId) => void }) {
  const [appointments, setAppointments] = useState<AppointmentSummary[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [materials, setMaterials] = useState<MaterialItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const rows = await getAppointments();
      setAppointments(rows);
      const preferred = rows.find(row => row.status === 'CONFIRMED') ?? rows[0];
      setSelectedId(preferred?.appointmentId ?? '');
      const names = preferred?.materials ?? [];
      setMaterials(names.map((label, index) => ({
        id: 'material-' + (preferred?.appointmentId ?? 'none') + '-' + index,
        label,
        prepared: false,
        required: index < 4,
      })));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取复诊事项');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, []);

  const toggle = (id: string) => {
    setMaterials(items => items.map(item => item.id === id ? { ...item, prepared: !item.prepared } : item));
  };
  const done = materials.filter(item => item.prepared).length;
  const appointment = appointments.find(item => item.appointmentId === selectedId) ?? appointments[0];

  const selectAppointment = (row: AppointmentSummary) => {
    setSelectedId(row.appointmentId);
    setMaterials(row.materials.map((label, index) => ({
      id: 'material-' + row.appointmentId + '-' + index, label, prepared: false, required: index < 4,
    })));
  };

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="复诊事项" onBack={() => onNavigate('home')} />

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取事项…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && !appointment && (
        <section className="rounded-3xl border border-[#efcda4] bg-[#fff8ed] px-6 py-10 text-center shadow-sm">
          <span className="mx-auto grid size-20 place-items-center rounded-full bg-[#ffe2bf] text-primary"><CalendarPlus className="size-10" /></span>
          <h2 className="mt-5 text-2xl font-bold">还没有复诊事项</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">完成预约并确认后，医院、时间、材料和提醒会显示在这里。</p>
          <button onClick={() => onNavigate('assistant')} className="mt-6 min-h-14 w-full rounded-2xl bg-primary px-5 text-lg font-bold text-white">让助手帮我预约</button>
        </section>
      )}

      {!loading && appointment && (
        <>
          <section className="rounded-3xl border bg-card p-4 shadow-sm">
            <div className="mb-3 flex items-center gap-2"><History className="size-6 text-primary" /><h2 className="text-xl font-bold">我的复诊预约记录</h2></div>
            <div className="grid gap-3">
              {appointments.map(row => (
                <button key={row.appointmentId} onClick={() => selectAppointment(row)} className={'rounded-2xl border p-4 text-left ' + (selectedId === row.appointmentId ? 'border-primary bg-[#fff3e4] ring-1 ring-primary/20' : 'bg-white')}>
                  <div className="flex items-start justify-between gap-3">
                    <div><strong className="text-lg">{formatDate(row.date)} {formatTime(row.time)}</strong><p className="mt-1 text-base">{row.hospital} · {row.department}</p></div>
                    <span className={'shrink-0 rounded-full px-3 py-1 text-sm font-bold ' + (row.status === 'CONFIRMED' ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-600')}>{row.status === 'CONFIRMED' ? '已预约' : '已取消'}</span>
                  </div>
                </button>
              ))}
            </div>
          </section>

          <section className="rounded-3xl bg-[#fff0dc] p-5 ring-1 ring-[#efcda4]">
            <div className="flex items-center gap-3">
              <div className="grid size-12 place-items-center rounded-2xl bg-primary text-white"><Clock3 /></div>
              <div>
                <p className="text-base text-muted-foreground">{appointment.status === 'CONFIRMED' ? '当前选择的复诊预约' : '已取消的预约记录'}</p>
                <h2 className="text-xl font-bold">{formatDate(appointment.date)} {formatTime(appointment.time)}</h2>
              </div>
            </div>
            <div className="mt-4 space-y-2 text-base">
              <p className="flex items-center gap-2"><MapPin className="size-5 text-primary" />{appointment.hospital} · {appointment.department}</p>
              <p className="flex items-center gap-2"><Navigation className="size-5 text-primary" />{appointment.departureAt ? `建议 ${formatDateTime(appointment.departureAt)} 出发` : '未设置出行提醒'}</p>
            </div>
          </section>

          <section className="rounded-3xl border bg-card p-5 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-xl font-bold">材料清单</h2>
              <span className="rounded-full bg-secondary px-3 py-1 text-sm font-semibold">已准备 {done}/{materials.length}</span>
            </div>
            <p className="mt-1 text-base text-muted-foreground">点一下可标记为已准备</p>
            <div className="mt-4 divide-y">
              {materials.map(item => (
                <button key={item.id} onClick={() => toggle(item.id)} className="flex min-h-14 w-full items-center gap-3 py-3 text-left">
                  <span className={`grid size-7 shrink-0 place-items-center rounded-full border-2 ${item.prepared ? 'border-primary bg-primary text-white' : 'border-muted-foreground/50'}`}>
                    {item.prepared ? <Check className="size-4" /> : <Circle className="size-3 opacity-0" />}
                  </span>
                  <span className={`text-lg ${item.prepared ? 'text-muted-foreground line-through' : 'font-semibold'}`}>{item.label}</span>
                  {item.required && <span className="ml-auto text-sm text-primary">必带</span>}
                </button>
              ))}
            </div>
          </section>

          <section className="grid gap-3">
            <div className="flex items-center gap-4 rounded-3xl border bg-card p-4"><BellRing className="size-7 text-primary" /><div><strong className="text-lg">{appointment.reminderStatus ?? '未创建提醒'}</strong><p className="text-sm text-muted-foreground">以数据库中的执行结果为准</p></div></div>
            <div className="flex items-center gap-4 rounded-3xl border bg-card p-4"><UsersRound className="size-7 text-primary" /><div><strong className="text-lg">家属通知</strong><p className="text-sm text-muted-foreground">{appointment.familyStatus ?? '无需通知家属'}</p></div></div>
          </section>
        </>
      )}
    </main>
  );
}

function formatDate(value: string) {
  const [, month, day] = value.split('-');
  return `${Number(month)}月${Number(day)}日`;
}

function formatTime(value: string) {
  return value.slice(0, 5);
}

function formatDateTime(value: string) {
  return value.includes('T') ? value.split('T')[1].slice(0, 5) : value.slice(11, 16);
}
