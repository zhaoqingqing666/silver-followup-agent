'use client';

import { CalendarDays, ChevronRight, MapPin, Route, UsersRound } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import type { AppointmentSummary, CareElder } from '@/types/domain';
import { CareMaterialStatus } from './care-material-status';
import { appointmentStatusChip, formatDate, formatHM } from './format';

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

const weekdayOf = (date: string): string => WEEKDAYS[new Date(`${date}T00:00:00`).getDay()];

/** 照护者只读查看某次复诊安排：去哪、何时、需带材料等；不提供任何写操作。 */
export function CareAppointmentDetailView({ caregiverId, elder, appointment, onBack, onManageUpcoming = null }: {
  caregiverId: string;
  elder: CareElder;
  appointment: AppointmentSummary;
  onBack: () => void;
  /** 传入时，底部给出“调整这次复诊”直达（有进行中预约的可调整场景）；为 null 时只显示提示文案。 */
  onManageUpcoming?: (() => void) | null;
}) {
  const participating = appointment.accompaniedBy === caregiverId;
  /** 已预约的不用挂标签；其余走和列表页同一份判定，免得同一条预约两张页面两个说法。 */
  const statusChip = appointment.status !== 'CONFIRMED' ? appointmentStatusChip(appointment.status) : null;

  return (
    <main className="space-y-5 px-5 pb-8 pt-5">
      <PageHeader title="复诊详情" subtitle={`${elder.relationship ?? ''}${elder.name}的这次复诊`} onBack={onBack} hideHelp />

      <section className="rounded-3xl border bg-card p-5 shadow-sm">
        <div className="flex gap-4">
          <div className="grid min-w-16 place-items-center rounded-2xl bg-secondary px-2 py-2 text-center text-secondary-foreground">
            <strong className="text-2xl">{Number(appointment.date.split('-')[2])}</strong>
            <span className="text-sm">{Number(appointment.date.split('-')[1])}月</span>
          </div>
          <div className="min-w-0 flex-1">
            <p className="flex items-start justify-between gap-2">
              <span className="text-xl font-bold">{formatHM(appointment.time)} {formatDate(appointment.date)} {weekdayOf(appointment.date)} 复诊</span>
              {statusChip && (
                <span className={`shrink-0 rounded-full px-3 py-1 text-sm font-bold ${statusChip.className}`}>{statusChip.label}</span>
              )}
            </p>
            <p className="mt-1 text-base">{appointment.hospital}</p>
            <p className="mt-1 text-base text-muted-foreground">{appointment.department}</p>
            {appointment.arrangedLabel && <p className="mt-1 text-sm text-muted-foreground">由{appointment.arrangedLabel}约好</p>}
            {participating && (
              <p className="mt-1 flex items-center gap-1.5 text-sm font-semibold text-primary">
                <UsersRound className="size-4" />您将陪同这次复诊
              </p>
            )}
          </div>
        </div>

        <div className="mt-4 space-y-2 border-t border-border/70 pt-4 text-sm text-muted-foreground">
          <p className="flex items-center gap-2"><MapPin className="size-4 shrink-0 text-primary" />{appointment.hospital} · {appointment.department}</p>
          {participating && appointment.departureAt && (
            <p className="flex items-center gap-2"><Route className="size-4 shrink-0 text-primary" />建议 {formatHM(appointment.departureAt)} 出发{appointment.transport ? ` · ${appointment.transport}` : ''}</p>
          )}
          {appointment.reminderStatus && (
            <p className="flex items-center gap-2"><CalendarDays className="size-4 shrink-0 text-primary" />复诊提醒：{appointment.reminderStatus}</p>
          )}
        </div>
      </section>

      {/* 材料这块从「一串名字」升级成「准备到哪一步」：家属人不在跟前，就是来远程确认东西备齐没有的 */}
      <CareMaterialStatus
        elderId={elder.elderId}
        appointmentId={appointment.appointmentId}
        fallbackLabels={appointment.materials}
        requiredMaterials={appointment.requiredMaterials}
      />

      <section className="rounded-3xl border bg-card p-5 shadow-sm">
        {onManageUpcoming ? (
          <button type="button" onClick={onManageUpcoming} className="mt-5 flex min-h-12 w-full items-center justify-center gap-1 rounded-2xl bg-primary px-4 text-base font-bold text-white shadow-lg shadow-primary/25 active:scale-[0.99]">
            调整这次复诊<ChevronRight className="size-5" />
          </button>
        ) : (
          <p className="mt-3 rounded-2xl bg-muted/60 px-4 py-3 text-sm leading-6 text-muted-foreground">
            需要改动这次复诊（改期或取消）时，请回到“帮助预约”，选择就诊人后即可调整。
          </p>
        )}
      </section>
    </main>
  );
}
