import { CalendarCheck2, Check, ClipboardList, Clock3, MapPin, UsersRound } from 'lucide-react';
import type { AgentConfirmationCard, AgentPlanCard, AgentResultCard } from '@/types/domain';

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
  return <section className="rounded-3xl border-2 border-primary/35 bg-[#fff2df] p-5 shadow-sm">
    <p className="text-sm font-bold text-primary">关键操作确认</p>
    <h2 className="mt-1 text-xl font-bold">{card.title}</h2>
    <ul className="mt-3 space-y-2">
      {card.operations.map(item => <li key={item} className="flex gap-2 text-base leading-7"><Check className="mt-1 size-5 shrink-0 text-primary" />{item}</li>)}
    </ul>
    <p className="mt-3 rounded-2xl bg-white/75 p-3 text-sm leading-6 text-muted-foreground">可能影响：{card.impact}</p>
    <button disabled={busy} onClick={onConfirm} className="mt-4 min-h-14 w-full rounded-2xl bg-primary px-4 text-lg font-bold text-white disabled:opacity-50">{busy ? '正在办理…' : card.confirmText}</button>
    <button disabled={busy} onClick={onCancel} className="mt-2 min-h-12 w-full rounded-2xl border bg-white text-base font-semibold">{card.cancelText}</button>
  </section>;
}

export function ResultCardView({ result, partial = false }: { result: AgentResultCard; partial?: boolean }) {
  return <section className="rounded-3xl border border-[#b7d6b0] bg-[#edf8e9] p-5 shadow-sm">
    <div className="flex items-center gap-3"><span className="grid size-12 place-items-center rounded-full bg-[#4f8548] text-white"><Check /></span><div><p className="text-sm font-semibold text-green-800">{partial ? '预约已保留，部分事项待补办' : '办理完成'}</p><h2 className="text-xl font-bold">复诊事项卡</h2></div></div>
    <div className="mt-4 space-y-3 text-[17px] leading-7">
      <p className="flex gap-3"><CalendarCheck2 className="mt-1 size-5 shrink-0 text-green-800" /><span><strong>{result.date} {result.time}</strong><br />{result.hospital} · {result.department}</span></p>
      <p className="flex gap-3"><Clock3 className="mt-1 size-5 shrink-0 text-green-800" /><span>建议出发：<strong>{result.departureTime ?? '未提供出发建议'}</strong><br />{result.reminderStatus}</span></p>
      <p className="flex gap-3"><UsersRound className="mt-1 size-5 shrink-0 text-green-800" /><span>{result.familyStatus}</span></p>
      <div className="flex gap-3"><ClipboardList className="mt-1 size-5 shrink-0 text-green-800" /><div><strong>携带材料</strong><p>{result.materials.join('、')}</p></div></div>
      <p className="flex gap-3 text-sm text-muted-foreground"><MapPin className="mt-1 size-4 shrink-0" />模拟预约编号：{result.appointmentId}</p>
    </div>
  </section>;
}
