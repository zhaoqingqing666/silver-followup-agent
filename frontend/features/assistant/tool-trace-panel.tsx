import { ChevronDown, Wrench } from 'lucide-react';
import type { ToolTrace } from '@/types/domain';

/** 参数是模型实时生成的 JSON 字符串；格式化只为好读，内容一个字符都不改。 */
function pretty(json: string | undefined): string {
  if (!json) return '（无）';
  try {
    return JSON.stringify(JSON.parse(json), null, 2);
  } catch {
    return json;
  }
}

/**
 * 智能体执行过程：把后端返回的真实执行轨迹按"分析 → 调用 → 结果 → 整理 → 最终回答"呈现。
 *
 * 这里展示的每一个工具名、参数、结果都来自后端运行时的真实记录（tool_call_logs），
 * 不是预设文案；展示的也只是执行事实，不包含模型内部思维链。
 * 面板默认收起，老人主界面仍然只有一句自然语言回答。
 */
export function ToolTracePanel({ traces, answer }: { traces: ToolTrace[]; answer?: string }) {
  if (!traces.length) return null;
  const calls = traces.filter(trace => trace.toolName !== 'vision.recognize');
  const failed = traces.some(trace => !trace.success);
  return <details className="rounded-2xl border border-[#e6cdb1] bg-white/80 px-4 py-3">
    <summary className="flex cursor-pointer list-none items-center gap-2 font-semibold text-[#6d432c]">
      <Wrench className="size-5" /> 智能体执行过程
      <span className="ml-auto rounded-full bg-[#f4dfc8] px-2 py-0.5 text-sm">{traces.length} 步</span>
      <ChevronDown className="size-4" />
    </summary>

    <div className="mt-3 space-y-3">
      <p className="flex items-start gap-2 text-sm leading-6 text-[#6d432c]">
        <span aria-hidden="true">🧠</span>
        <span>正在分析您的问题……</span>
      </p>

      {traces.map((trace, index) => <div key={index} className="rounded-xl bg-[#fff8ef] p-3 text-sm leading-6">
        <p className="flex flex-wrap items-baseline gap-x-2">
          <span aria-hidden="true">🔧</span>
          <strong>调用：{trace.label || trace.toolName}</strong>
          <code className="rounded bg-[#f4dfc8] px-1.5 py-0.5 text-xs text-[#8a6a52]">{trace.toolName}</code>
        </p>
        <p className="mt-1 break-all text-[#8a6a52]">
          参数（由模型实时生成）：
          <code className="ml-1 rounded bg-white px-1.5 py-0.5 text-xs">{pretty(trace.parameters)}</code>
        </p>
        <p className={`mt-1 ${trace.success ? 'text-green-700' : 'text-red-700'}`}>
          <span aria-hidden="true">{trace.success ? '✅' : '⚠️'}</span> 执行结果：{trace.summary}
        </p>
        <details className="mt-1">
          <summary className="cursor-pointer list-none text-xs text-[#a87e5c] underline">查看原始返回数据</summary>
          <pre className="mt-1 max-h-40 overflow-auto rounded-lg bg-white p-2 text-xs leading-5 text-[#8a6a52]">{pretty(trace.result)}</pre>
        </details>
      </div>)}

      {!!calls.length && <p className="flex items-start gap-2 text-sm leading-6 text-[#6d432c]">
        <span aria-hidden="true">🧠</span>
        <span>{failed ? '已拿到工具返回结果，正在整理……' : '已获取相关资料，正在整理……'}</span>
      </p>}

      {answer && <div className="rounded-xl border border-[#e6cdb1] bg-[#fffdf9] p-3 text-sm leading-6">
        <p className="font-semibold text-[#6d432c]"><span aria-hidden="true">💬</span> 最终回答</p>
        <p className="mt-1 whitespace-pre-wrap text-[#6c3d24]">{answer}</p>
      </div>}
    </div>
  </details>;
}
