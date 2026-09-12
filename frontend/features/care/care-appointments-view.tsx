'use client';

import { useEffect, useState } from 'react';
import { CalendarDays, ChevronRight, Clock3, LoaderCircle, RefreshCw, Route, TriangleAlert, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { getAppointments } from '@/lib/appointment-api';
import type { AppointmentSummary, CareElder } from '@/types/domain';
import { formatDate, formatHM } from './format';
import { CareAppointmentDetailView } from './care-appointment-detail-view';

interface CareAppointmentsProps {
  caregiverId: string;
  elder: CareElder;
  onBack: () => void;
  /** 有进行中预约时，详情页提供“调整这次复诊”直达入口（从管理视图查看则不传）。 */
  onManageUpcoming?: (elder: CareElder) => void;
}

/** 本地时区的今天（yyyy-MM-dd），用于判断预约是否还在未来。 */
const todayLocal = (): string => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
};

function statusChip(status: string) {
  if (status === 'CONFIRMED') return { label: '已预约', className: 'bg-green-100 text-green-800' };
  if (status === 'CANCELLED') return { label: '已取消', className: 'bg-gray-100 text-gray-600' };
  return { label: '处理中', className: 'bg-amber-100 text-amber-800' };
}

/** 某位长辈的复诊预约卡片页：只有参与这次就诊的照护者才看到出发建议，其余走“查看详情”。 */
export function CareAppointmentsView({ caregiverId, elder, onBack, onManageUpcoming }: CareAppointmentsProps) {
  const [appointments, setAppointments] = useState<AppointmentSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [detail, setDetail] = useState<AppointmentSummary | null>(null);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setAppointments(await getAppointments(elder.elderId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取复诊预约');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [elder.elderId]);

  if (detail) {
    // 只有“进行中且未过期”的预约才提供直达调整入口；历史/已取消仅展示
    const manageable = onManageUpcoming != null
      && detail.status === 'CONFIRMED'
      && detail.date >= todayLocal();
    return (
      <CareAppointmentDetailView
        caregiverId={caregiverId}
        elder={elder}
        appointment={detail}
        onBack={() => setDetail(null)}
        onManageUpcoming={manageable ? () => onManageUpcoming!(elder) : null}
      />
    );
  }

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="复诊预约" onBack={onBack} hideHelp />

      {elder.alert && (
        <p className="flex items-start gap-2 rounded-2xl bg-red-50 px-4 py-3 text-[15px] font-semibold text-red-700 ring-1 ring-red-200">
          <TriangleAlert className="mt-0.5 size-5 shrink-0" />{elder.alert}
        </p>
      )}

      {loading && (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-8 animate-spin" /><p className="mt-3 text-lg">正在读取预约…</p></div>
        </section>
      )}

      {!loading && error && (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{error}</p>
          <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
        </section>
      )}

      {!loading && !error && appointments.length === 0 && (
        <section className="rounded-3xl border bg-card px-6 py-10 text-center shadow-sm">
          <CalendarDays className="mx-auto size-10 text-muted-foreground" />
          <h2 className="mt-4 text-xl font-bold">还没有复诊预约</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">可以由就诊人在复诊助手里办理，也可以回到首页用“帮助预约”帮 ta 代约；预约成功后卡片会显示在这里。</p>
        </section>
      )}

      {!loading && !error && appointments.length > 0 && (
        <section className="grid gap-3">
          {appointments.map(row => {
            const chip = statusChip(row.status);
            // 只有进行中且该照护者确认陪同的预约才显示出发建议
            const participating = row.status === 'CONFIRMED' && row.accompaniedBy === caregiverId;
            return (
              <article key={row.appointmentId} className="rounded-3xl border bg-card p-4 shadow-sm">
                <div className="flex gap-4">
                  <div className="grid min-w-16 place-items-center rounded-2xl bg-secondary px-2 py-2 text-center text-secondary-foreground">
                    <strong className="text-xl">{Number(row.date.split('-')[2])}</strong>
                    <span className="text-sm">{Number(row.date.split('-')[1])}月</span>
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-2">
                      <p className="text-lg font-bold">{formatHM(row.time)} {formatDate(row.date)} 复诊</p>
                      <span className={`shrink-0 rounded-full px-3 py-1 text-sm font-bold ${chip.className}`}>{chip.label}</span>
                    </div>
                    <p className="mt-1 truncate text-base">{row.hospital}</p>
                    <p className="mt-1 truncate text-base text-muted-foreground">{row.department}</p>
                  </div>
                </div>

                <div className="mt-3 space-y-1.5 border-t border-border/70 pt-3 text-sm text-muted-foreground">
                  {participating ? (
                    <>
                      {row.departureAt && (
                        <p className="flex items-center gap-2"><Route className="size-4 shrink-0 text-primary" />建议 {formatHM(row.departureAt)} 出发{row.transport ? ` · ${row.transport}` : ''}</p>
                      )}
                      <p className="flex items-center gap-2"><UsersRound className="size-4 shrink-0 text-primary" />您将陪同这次复诊</p>
                      {row.reminderStatus && (
                        <p className="flex items-center gap-2"><Clock3 className="size-4 shrink-0 text-primary" />复诊提醒：{row.reminderStatus}</p>
                      )}
                    </>
                  ) : null}
                </div>

                <button
                  type="button"
                  onClick={() => setDetail(row)}
                  className="mt-3 flex min-h-12 w-full items-center justify-center gap-1 rounded-2xl bg-muted px-4 text-base font-bold text-foreground active:scale-[0.99]"
                >
                  查看详情<ChevronRight className="size-5" />
                </button>
              </article>
            );
          })}
        </section>
      )}
    </main>
  );
}
