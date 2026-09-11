'use client';

import { Check, Circle, LoaderCircle, RefreshCw } from 'lucide-react';
import { useAppointmentMaterials } from '@/hooks/use-appointment-materials';

export function MaterialChecklist({ appointmentId, disabled = false, compact = false }: {
  appointmentId: string;
  disabled?: boolean;
  compact?: boolean;
}) {
  const { materials, loading, updatingId, error, reload, toggle } = useAppointmentMaterials(appointmentId);
  const done = materials.filter(item => item.status !== 'NOT_PREPARED').length;

  if (loading) return <div className="flex items-center gap-2 py-4 text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />正在读取材料状态…</div>;

  return <section className={compact ? 'mt-4 rounded-2xl bg-white/70 p-4' : 'rounded-3xl border bg-card p-5 shadow-sm'}>
    <div className="flex items-center justify-between gap-3">
      <h2 className={compact ? 'font-bold' : 'text-xl font-bold'}>材料清单</h2>
      <span className="rounded-full bg-secondary px-3 py-1 text-sm font-semibold">已准备 {done}/{materials.length}</span>
    </div>
    {!compact && <p className="mt-1 text-base text-muted-foreground">点一下会保存到数据库，刷新后仍会保留</p>}
    {error && <div className="mt-3 flex items-center justify-between gap-2 rounded-2xl bg-red-50 p-3 text-sm text-red-700"><span>{error}</span><button onClick={() => void reload()} aria-label="重新读取材料"><RefreshCw className="size-5" /></button></div>}
    <div className="mt-3 divide-y">
      {materials.map(item => {
        const prepared = item.status !== 'NOT_PREPARED';
        const updating = updatingId === item.id;
        return <button key={item.id} disabled={disabled || !!updatingId} onClick={() => void toggle(item)} className="flex min-h-14 w-full items-center gap-3 py-3 text-left disabled:opacity-60">
          <span className={`grid size-7 shrink-0 place-items-center rounded-full border-2 ${prepared ? 'border-primary bg-primary text-white' : 'border-muted-foreground/50'}`}>
            {updating ? <LoaderCircle className="size-4 animate-spin" /> : prepared ? <Check className="size-4" /> : <Circle className="size-3 opacity-0" />}
          </span>
          <span className={`${compact ? 'text-base' : 'text-lg'} ${prepared ? 'text-muted-foreground line-through' : 'font-semibold'}`}>{item.materialName}</span>
          {item.status === 'PHOTO_CONFIRMED' && <span className="ml-auto text-sm text-green-700">拍照确认</span>}
          {item.required && item.status !== 'PHOTO_CONFIRMED' && <span className="ml-auto text-sm text-primary">必带</span>}
        </button>;
      })}
      {!materials.length && !error && <p className="py-4 text-muted-foreground">暂无材料记录</p>}
    </div>
  </section>;
}
