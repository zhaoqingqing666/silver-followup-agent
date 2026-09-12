import { CalendarCheck2, Check, ChevronRight, ClipboardList, Clock3, MapPin, Route, ShieldAlert, UsersRound } from 'lucide-react';
import { MEDICAL_BOUNDARY_NOTICE } from '@/types/domain';
import type { AgentConfirmationCard, AgentNotice, AgentPlanCard, AgentResultCard } from '@/types/domain';
import { MaterialChecklist } from '@/features/materials/material-checklist';

/**
 * 医疗越界的专属提示块。
 *
 * 越界回复本身照常留在对话记录里（后端把它存进 conversation_messages），
 * 这张卡只是另外把「为什么这条不一样」说出来，所以卡里不再重复一遍回复正文。
 * 未知 type 一律不渲染——后端加新提示类型时，旧版前端不会因此显示一块空白。
 *
 * 不在卡里放任何按钮：越界不改变办理流程，确认卡里那个待确认的操作依旧有效，
 * 老人要是想接着办，页面上原有的按钮就够了。
 */
export function BoundaryAlert({ notice }: { notice: AgentNotice }) {
  if (notice.type !== MEDICAL_BOUNDARY_NOTICE) return null;
  return <section role="alert" aria-label={notice.title}
    className="rounded-3xl border-2 border-[#e0a24a] bg-[#fff3e0] p-4 shadow-sm">
    <div className="flex items-center gap-3">
      <span className="grid size-11 shrink-0 place-items-center rounded-full bg-[#c8862f] text-white"><ShieldAlert className="size-6" /></span>
      <p className="text-lg font-bold text-[#8a5410]">{notice.title}</p>
    </div>
    <p className="mt-2 text-base leading-7 text-[#6c3d24]">{notice.message}</p>
  </section>;
}

export function PlanCard({ plan }: { plan: AgentPlanCard }) {
  return <section className="rounded-3xl border border-[#e8c7a4] bg-[#fff8ed] p-5 shadow-sm">
    <p className="text-sm font-bold text-primary">复诊办理计划</p>
    <h2 className="mt-1 text-xl font-bold">{plan.hospital} · {plan.department}</h2>
    <p className="mt-2 text-lg"><strong>{plan.date} {plan.selectedTime}</strong></p>
    <p className="mt-2 text-base">建议出发：{plan.departureTime} · 通知对象：{plan.familyContact}</p>
    <div className="mt-4 grid gap-2">
      {plan.tasks.map((task, index) => <div key={task} className="flex items-center gap-3 rounded-2xl bg-white/80 px-3 py-2">
        <span className="grid size-7 shrink-0 place-items-center rounded-full bg-[#f3ddc5] font-bold text-primary">{index + 1}</span>
        <span className="flex-1 text-base">{task}</span><span className="text-sm font-semibold">{plan.taskStatuses?.[index] ?? '待确认'}</span>
      </div>)}
    </div>
    {!!plan.materials.length && <div className="mt-4 rounded-2xl border border-[#efd4b3] bg-white p-4">
      <div className="flex items-center gap-2 font-bold text-[#5d3c29]"><ClipboardList className="size-5 text-primary" />复诊材料清单</div>
      <ul className="mt-2 grid gap-2">
        {plan.materials.map((material) => <li key={material} className="flex items-start gap-2 text-base leading-6">
          <Check className="mt-0.5 size-5 shrink-0 text-primary" />
          <span>{material}</span>
        </li>)}
      </ul>
    </div>}
  </section>;
}

export function ConfirmationCardView({ card, busy, onConfirm, onCancel }: {
  card: AgentConfirmationCard; busy: boolean; onConfirm: () => void; onCancel: () => void;
}) {
  const destructive = card.confirmText.includes('取消');
  return <section className="rounded-3xl border-2 border-primary/35 bg-[#fff2df] p-5 shadow-sm">
    <p className="text-sm font-bold text-primary">关键操作确认</p>
    <h2 className="mt-1 text-xl font-bold">{card.title}</h2>
    <ul className="mt-3 space-y-2">
      {card.operations.map(item => <li key={item} className="flex gap-2 text-base leading-7"><Check className="mt-1 size-5 shrink-0 text-primary" />{item}</li>)}
    </ul>
    <p className="mt-3 rounded-2xl bg-white/75 p-3 text-sm leading-6 text-muted-foreground">可能影响：{card.impact}</p>
    <button disabled={busy} onClick={onConfirm}
      className={`mt-4 min-h-14 w-full rounded-2xl px-4 text-lg font-bold text-white shadow-sm disabled:opacity-50 ${destructive ? 'bg-[#b42318]' : 'bg-primary'}`}>
      {busy ? '正在办理…' : card.confirmText}
    </button>
    <button disabled={busy} onClick={onCancel}
      className={`mt-2 min-h-12 w-full rounded-2xl border px-4 text-base font-bold ${destructive ? 'border-[#2f6f3e] bg-[#2f6f3e] text-white' : 'bg-white'}`}>
      {card.cancelText}
    </button>
  </section>;
}

export function ResultCardView({ result, partial = false, onOpenTravel }: {
  result: AgentResultCard;
  partial?: boolean;
  onOpenTravel?: (appointmentId: string) => void;
}) {
  return <section className="rounded-3xl border border-[#b7d6b0] bg-[#edf8e9] p-5 shadow-sm">
    <div className="flex items-center gap-3"><span className="grid size-12 place-items-center rounded-full bg-[#4f8548] text-white"><Check /></span><div><p className="text-sm font-semibold text-green-800">{partial ? '预约已保留，部分事项待补办' : '办理完成'}</p><h2 className="text-xl font-bold">复诊事项卡</h2></div></div>
    <div className="mt-4 space-y-3 text-[17px] leading-7">
      <p className="flex gap-3"><CalendarCheck2 className="mt-1 size-5 shrink-0 text-green-800" /><span><strong>{result.date} {result.time}</strong><br />{result.hospital} · {result.department}</span></p>
      <p className="flex gap-3"><Clock3 className="mt-1 size-5 shrink-0 text-green-800" /><span>建议出发：<strong>{result.departureTime ?? '未提供出发建议'}</strong><br />{result.reminderStatus}</span></p>
      <p className="flex gap-3"><UsersRound className="mt-1 size-5 shrink-0 text-green-800" /><span>{result.familyStatus}</span></p>
      <p className="flex gap-3 text-sm text-muted-foreground"><MapPin className="mt-1 size-4 shrink-0" />模拟预约编号：{result.appointmentId}</p>
    </div>
    {onOpenTravel && <button onClick={() => onOpenTravel(result.appointmentId)}
      className="mt-4 flex min-h-14 w-full items-center justify-between rounded-2xl bg-[#4f8548] px-4 text-left text-white">
      <span className="flex items-center gap-3"><Route className="size-6" /><span><strong className="block text-lg">查看出行路线</strong><span className="text-sm text-white/80">包含楼层和诊室指引</span></span></span>
      <ChevronRight className="size-6" />
    </button>}
    <MaterialChecklist appointmentId={result.appointmentId} compact />
  </section>;
}
