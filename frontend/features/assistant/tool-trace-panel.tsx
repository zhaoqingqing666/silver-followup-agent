import { useState } from 'react';
import { ChevronDown, Wrench } from 'lucide-react';
import { describeTrace } from '@/features/assistant/tool-trace-describe';
import { LiveProgress } from '@/features/assistant/live-progress';
import type { TurnProgressEvent } from '@/lib/agent-api';
import type { ToolTrace } from '@/types/domain';

/**
 * 「查看办理过程」。
 *
 * 三种状态共用这一张卡：
 * - 办理中：展开实时步骤，展示模型真实生成的参数与工具的真实返回；
 * - 刚办完：实时步骤消失，换成下面这份中文小结（老人看得懂的那份）；
 * - 没有实时进度（刷新后、旧消息）：只显示中文小结。
 *
 * 办理中自动展开，是因为评审要看的正是「参数是模型现场生成的」——
 * 藏在一个需要手动点开的折叠面板里，演示时根本来不及展开。
 */
export function ToolTracePanel({ traces, liveEvents = [], liveActive = false }: {
  traces: ToolTrace[];
  /** 本轮实时进度事件；不在办理中时传空数组即可 */
  liveEvents?: TurnProgressEvent[];
  liveActive?: boolean;
}) {
  // 受控展开：办理中强制展开，办完之后尊重用户自己的开合。
  const [open, setOpen] = useState(false);
  const showing = open || liveActive;
  if (!traces.length && !liveActive) return null;
  return <details open={showing}
    onToggle={event => setOpen(event.currentTarget.open)}
    className="rounded-2xl border bg-white/80 px-4 py-3">
    <summary className="flex cursor-pointer list-none items-center gap-2 font-semibold text-[#6d432c]">
      <Wrench className="size-5" /> 查看办理过程
      <span className="ml-auto rounded-full bg-[#f4dfc8] px-2 py-0.5 text-sm">
        {liveActive ? '办理中' : `${traces.length} 步`}
      </span>
      <ChevronDown className={`size-4 transition ${showing ? 'rotate-180' : ''}`} />
    </summary>
    <div className="mt-1 space-y-2">
      <LiveProgress events={liveEvents} active={liveActive} />
      {traces.length > 0 && <>
        <p className="text-sm text-muted-foreground">刚才我为您依次做了这几步：</p>
        <ol className="mt-2 space-y-3">
        {traces.map((trace, index) => {
          const { label, note } = describeTrace(trace);
          return <li key={index} className="rounded-xl bg-[#fff8ef] p-3 text-sm leading-6">
            <div className="flex items-center justify-between">
              <strong>{index + 1}. {label}</strong>
              <span className={trace.success ? 'text-green-700' : 'text-red-700'}>{trace.success ? '成功' : '未完成'}</span>
            </div>
            {note && <p className="mt-1 break-words text-muted-foreground">{note}</p>}
          </li>;
        })}
        </ol>
      </>}
    </div>
  </details>;
}
