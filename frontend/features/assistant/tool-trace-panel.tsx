import { ChevronDown, Wrench } from 'lucide-react';
import type { ToolTrace } from '@/types/domain';

export function ToolTracePanel({ traces }: { traces: ToolTrace[] }) {
  // 老会话快照里可能没有这个字段，按空处理而不是直接读 length。
  if (!traces?.length) return null;
  return <details className="rounded-2xl border bg-white/80 px-4 py-3">
    <summary className="flex cursor-pointer list-none items-center gap-2 font-semibold text-[#6d432c]">
      <Wrench className="size-5" /> 查看办理过程
      <span className="ml-auto rounded-full bg-[#f4dfc8] px-2 py-0.5 text-sm">{traces.length} 次调用</span>
      <ChevronDown className="size-4" />
    </summary>
    <div className="mt-3 space-y-3">
      {traces.map((trace, index) => <div key={index} className="rounded-xl bg-[#fff8ef] p-3 text-sm leading-6">
        <div className="flex items-center justify-between"><strong>{trace.toolName}</strong><span className={trace.success ? 'text-green-700' : 'text-red-700'}>{trace.success ? '成功' : '失败'}</span></div>
        <p className="mt-1 break-all text-muted-foreground">参数：{trace.parameters}</p>
        <p className="break-all text-muted-foreground">结果：{trace.result}</p>
      </div>)}
    </div>
  </details>;
}
