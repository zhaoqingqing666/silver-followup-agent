import { useEffect, useRef, useState } from 'react';
import { CalendarCheck2, Check, ClipboardList, Clock3, LoaderCircle, MapPin, UsersRound, Volume2, VolumeX } from 'lucide-react';
import { onPlaybackChange, speakText, stopPlayback } from '@/lib/tts-player';
import type { AgentConfirmationCard, AgentPlanCard, AgentResultCard } from '@/types/domain';
import { MaterialChecklist } from '@/features/materials/material-checklist';

export function PlanCard({ plan }: { plan: AgentPlanCard }) {
  return <section className="rounded-3xl border border-[#e8c7a4] bg-[#fff8ed] p-5 shadow-sm">
    <p className="text-sm font-bold text-primary">复诊办理计划</p>
    <h2 className="mt-1 text-xl font-bold">{plan.hospital} · {plan.department}</h2>
    <p className="mt-2 text-lg"><strong>{plan.date} {plan.selectedTime}</strong></p>
    <div className="mt-4 grid gap-2">
      {plan.tasks.map((task, index) => <div key={task} className="flex items-center gap-3 rounded-2xl bg-white/80 px-3 py-2">
        <span className="grid size-7 shrink-0 place-items-center rounded-full bg-[#f3ddc5] font-bold text-primary">{index + 1}</span>
        <span className="text-base">{task}</span>
      </div>)}
    </div>
    {!!plan.materials.length && <div className="mt-4 rounded-2xl border border-[#efd4b3] bg-white p-4">
      <div className="flex items-center gap-2 font-bold text-[#5d3c29]"><ClipboardList className="size-5 text-primary" />复诊材料清单</div>
      <ul className="mt-2 grid gap-2">
        {plan.materials.map((material, index) => <li key={material} className="flex items-start gap-2 text-base leading-6">
          <Check className="mt-0.5 size-5 shrink-0 text-primary" />
          <span>{material}{index < 4 && <strong className="ml-2 text-sm text-primary">必带</strong>}</span>
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

export function ResultCardView({ result }: { result: AgentResultCard }) {
  const [playing, setPlaying] = useState(false);
  const [loading, setLoading] = useState(false);
  // 整页结果朗读的唯一标识（与其它朗读源互斥）
  const keyRef = useRef<string>('result');

  useEffect(() => onPlaybackChange((isPlaying, src) => {
    setPlaying(isPlaying && src !== null && src === keyRef.current);
  }), []);

  /** 朗读整页办理结果（医院/时间/出发/材料），再点停止 */
  const speakResult = async () => {
    if (playing) { stopPlayback(); return; }
    setLoading(true);
    try {
      const summary = `您的复诊已经办好。${result.date}${result.time}，在${result.hospital}${result.department}就诊。建议${result.departureTime}出发，${result.reminderStatus}。${result.familyStatus}。需要携带的材料有：${result.materials.join('，')}。`;
      // 本地优先：浏览器中文语音直接播；不支持/没中文语音才回退云端
      await speakText('result', summary);
    } finally { setLoading(false); }
  };

  return <section className="rounded-3xl border border-[#b7d6b0] bg-[#edf8e9] p-5 shadow-sm">
    <div className="flex items-center justify-between gap-2">
      <div className="flex items-center gap-3"><span className="grid size-12 place-items-center rounded-full bg-[#4f8548] text-white"><Check /></span><div><p className="text-sm font-semibold text-green-800">办理完成</p><h2 className="text-xl font-bold">复诊事项卡</h2></div></div>
      <button onClick={() => void speakResult()} disabled={loading} aria-label={playing ? '停止朗读办理结果' : '朗读办理结果'} className="flex h-11 shrink-0 items-center gap-1.5 rounded-full bg-[#4f8548] px-4 text-sm font-bold text-white shadow-sm disabled:opacity-80">
        {loading ? <LoaderCircle className="size-5 animate-spin" /> : playing ? <VolumeX className="size-5" /> : <Volume2 className="size-5" />}
        <span>{playing ? '停止' : '朗读'}</span>
      </button>
    </div>
    <div className="mt-4 space-y-3 text-[17px] leading-7">
      <p className="flex gap-3"><CalendarCheck2 className="mt-1 size-5 shrink-0 text-green-800" /><span><strong>{result.date} {result.time}</strong><br />{result.hospital} · {result.department}</span></p>
      <p className="flex gap-3"><Clock3 className="mt-1 size-5 shrink-0 text-green-800" /><span>建议出发：<strong>{result.departureTime}</strong><br />{result.reminderStatus}</span></p>
      <p className="flex gap-3"><UsersRound className="mt-1 size-5 shrink-0 text-green-800" /><span>{result.familyStatus}</span></p>
      <div className="flex gap-3"><ClipboardList className="mt-1 size-5 shrink-0 text-green-800" /><div><strong>携带材料</strong><p>{result.materials.join('、')}</p></div></div>
      <p className="flex gap-3 text-sm text-muted-foreground"><MapPin className="mt-1 size-4 shrink-0" />模拟预约编号：{result.appointmentId}</p>
    </div>
    <MaterialChecklist appointmentId={result.appointmentId} compact />
  </section>;
}
