import { useState } from 'react';
import { ChevronDown, Wrench } from 'lucide-react';
import { describeTrace } from '@/features/assistant/tool-trace-describe';
import { BouncingDots, currentStepLabel, LiveProgress } from '@/features/assistant/live-progress';
import type { TurnProgressEvent } from '@/lib/agent-api';
import type { ToolTrace } from '@/types/domain';

/**
 * 「查看办理过程」。
 *
 * 三种状态共用这一张卡，正文一律默认收起，点标题才展开：
 * - 办理中：标题上换着显示此刻这一步（模型真实生成的参数、工具的真实返回都在正文里）；
 * - 刚办完：实时步骤消失，换成下面这份中文小结（老人看得懂的那份）；
 * - 没有实时进度（刷新后、旧消息）：只显示中文小结。
 *
 * 不再办理中自动展开：卡片展开时会把下面的计划卡、确认卡整体推下去，
 * 老人正要点「确认办理」时按钮突然跑位。所以办理中只在标题上留一行实时状态——
 * 想看参数的人点一下就有，不想看的人不会被挤到。
 */
export function ToolTracePanel({ traces, liveEvents = [], liveActive = false }: {
  /** 后端每轮都返回，但历史消息或旧快照里可能没有这个字段——按空处理，别直接读 length */
  traces?: ToolTrace[];
  /** 本轮实时进度事件；不在办理中时传空数组即可 */
  liveEvents?: TurnProgressEvent[];
  liveActive?: boolean;
}) {
  // 纯手动开合，办理中也不代用户打开。
  const [open, setOpen] = useState(false);
  const rows = traces ?? [];
  if (!rows.length && !liveActive) return null;
  // 思考类事件还没来时（刚发出这一轮），先给一句泛泛的「正在处理」，别让标题栏空着。
  const liveText = currentStepLabel(liveEvents) || '正在处理…';
  return <details open={open}
    onToggle={event => setOpen(event.currentTarget.open)}
    className="rounded-2xl border bg-white/80 px-4 py-3">
    <summary className="cursor-pointer list-none font-semibold text-[#6d432c]">
      <span className="flex items-center gap-2">
        <Wrench className="size-5" /> 查看办理过程
        <span className="ml-auto rounded-full bg-[#f4dfc8] px-2 py-0.5 text-sm">
          {liveActive ? '办理中' : `${rows.length} 步`}
        </span>
        <ChevronDown className={`size-4 transition ${open ? 'rotate-180' : ''}`} />
      </span>
      {/* 收起时唯一看得到的动静。屏幕阅读器由对话上方那句忙碌提示负责，这里不重复报。 */}
      {liveActive && <span className="mt-1 flex items-center gap-2 pl-7 text-sm font-normal text-muted-foreground">
        <BouncingDots />{liveText}
      </span>}
    </summary>
    <div className="mt-1 space-y-2">
      <LiveProgress events={liveEvents} active={liveActive} />
      {rows.length > 0 && <>
        <p className="text-sm text-muted-foreground">刚才我为您依次做了这几步：</p>
        <ol className="mt-2 space-y-3">
        {rows.map((trace, index) => {
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
