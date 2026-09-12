'use client';

import { Check, Loader2, X } from 'lucide-react';
import { describeTrace } from '@/features/assistant/tool-trace-describe';
import { toSteps, type Step } from '@/features/assistant/live-progress-steps';
import type { TurnProgressEvent } from '@/lib/agent-api';
import type { ToolTrace } from '@/types/domain';

/** 一轮里最多同时铺开几步。再多就只显示最近几步，免得面板一路长到屏幕外面。 */
const MAX_LIVE_STEPS = 4;
/** 参数/结果单块的最大高度，超出滚动查看；长结果不应该把这轮对话顶走。 */
const MAX_BLOCK_CHARS = 240;

/** 把 JSON 原文压成一眼能看清的样子；解析不了就原样显示（后端已经截断过长度）。 */
function pretty(raw: string | null): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw));
  } catch {
    return raw;
  }
}

const shorten = (text: string) =>
  text.length > MAX_BLOCK_CHARS ? `${text.slice(0, MAX_BLOCK_CHARS)}…` : text;

/** 三个跳动的点。用 aria-hidden 藏起来，给屏幕阅读器的是旁边那句文字。 */
function BouncingDots() {
  return <span className="flex items-center gap-1" aria-hidden="true">
    {[0, 1, 2].map(index => (
      <span key={index} className="size-1.5 animate-bounce rounded-full bg-primary"
        style={{ animationDelay: `${index * 150}ms` }} />
    ))}
  </span>;
}

function ToolStep({ step, isLast }: { step: Step; isLast: boolean }) {
  const pending = step.executedTool === null;
  // 复用「查看办理过程」的中文口径：执行完就能显示「医院目录 · 查询可办理医院」，
  // 不必为实时视图另写一套翻译。还没执行时只有模型给的意图名，如实显示原名。
  const probe: ToolTrace = {
    toolName: step.executedTool ?? step.proposedTool,
    parameters: step.parameters ?? '{}',
    result: step.result ?? 'null',
    success: step.success ?? true,
  };
  const { label, note: resultNote } = describeTrace(probe);
  // 结果还没回来时不能显示这句小结：它是照着「空结果」算出来的，
  // 会说出「共查到 0 个可约时段」这种结果到位前就是假话的话。
  const note = pending ? '' : resultNote;
  const request = pretty(step.parameters);
  const response = pretty(step.result);

  return <li className={`rounded-xl border p-3 transition-colors ${
    pending && isLast ? 'border-[#e6bc8c] bg-[#fff8ed]' : 'border-transparent bg-[#fff8ef]'
  }`}>
    <div className="flex items-center gap-2">
      {pending
        ? <Loader2 className="size-4 shrink-0 animate-spin text-primary" aria-hidden="true" />
        : step.success
          ? <Check className="size-4 shrink-0 text-green-700" aria-hidden="true" />
          : <X className="size-4 shrink-0 text-red-700" aria-hidden="true" />}
      <strong className="text-sm text-[#6d432c]">{label}</strong>
      {pending && <span className="ml-auto flex items-center gap-2 text-sm text-muted-foreground">
        <BouncingDots />调用中
      </span>}
      {!pending && <span className={`ml-auto text-sm font-semibold ${
        step.success ? 'text-green-700' : 'text-red-700'}`}>
        {step.success ? '成功' : '未完成'}
      </span>}
    </div>

    {/* 模型提出的是意图名，真正执行的是映射后的工具——两个名字不一样是正常的，
        而且这正说明了「模型只管提建议、调哪个工具由后端决定」。 */}
    {!pending && step.executedTool !== step.proposedTool && <p className="mt-1 text-xs text-muted-foreground">
      模型提出 {step.proposedTool} · 后端执行 {step.executedTool}
    </p>}

    {/* 模型真实生成的参数——这正是「不能只看预先写好的对话」要证明的东西。 */}
    {request && <p className="mt-2 break-all font-mono text-xs leading-5 text-[#8a5a3b]">
      <span className="font-sans font-bold">参数 </span>{shorten(request)}
    </p>}

    {note && <p className="mt-1 break-words text-sm leading-6 text-[#5b3a28]">{note}</p>}

    {response && <p className="mt-1 max-h-32 overflow-auto break-all rounded-lg bg-white/70 px-2 py-1 font-mono text-xs leading-5 text-muted-foreground">
      <span className="font-sans font-bold">结果 </span>{shorten(response)}
    </p>}
  </li>;
}

/**
 * 正在办理时的实时步骤。
 *
 * 一轮结束时调用方直接不再渲染它——「最后都会隐藏」，
 * 留下的那份中文办理过程由 {@code ToolTracePanel} 自己的列表负责。
 */
export function LiveProgress({ events, active }: { events: TurnProgressEvent[]; active: boolean }) {
  if (!active || !events.length) return null;
  const { steps, status } = toSteps(events);
  const hidden = Math.max(0, steps.length - MAX_LIVE_STEPS);
  const visible = steps.slice(-MAX_LIVE_STEPS);

  return <div aria-live="polite" className="rounded-xl border border-[#e6bc8c] bg-[#fffdf8] p-3">
    <p className="flex items-center gap-2 text-sm font-bold text-[#6d432c]">
      <BouncingDots />
      {status || '正在处理…'}
      <span className="ml-auto font-normal text-muted-foreground">实时</span>
    </p>
    {hidden > 0 && <p className="mt-1 text-xs text-muted-foreground">前面还有 {hidden} 步已折叠</p>}
    <ol className="mt-2 space-y-2">
      {visible.map((step, index) => (
        <ToolStep key={step.key} step={step} isLast={index === visible.length - 1} />
      ))}
    </ol>
  </div>;
}
