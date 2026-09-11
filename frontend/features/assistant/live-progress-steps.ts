/** 一轮里的一步工具调用，由事件流折叠而来。 */
export type Step = {
  key: number;
  /** 模型自己说出来的工具名（意图层，如 hospital.search） */
  proposedTool: string;
  /** 后端映射后真正执行的工具名（如 catalog.queryHospitals），还没执行时为 null */
  executedTool: string | null;
  parameters: string | null;
  result: string | null;
  success: boolean | null;
};

/** 只取折叠所需的最小字段，方便单独验证这段逻辑。 */
export type ProgressEvent = {
  seq: number;
  kind: string;
  tool: string | null;
  parameters: string | null;
  result: string | null;
  success: boolean | null;
  /** 思考类事件的说明文字，只有这些事件会用到 */
  text?: string | null;
};

/**
 * 把事件流折成「一步一次工具调用」。
 *
 * 后端在真正执行前发一条 TOOL_PROPOSED（此时参数已知、结果还没有），执行完再发
 * TOOL_RESULT。这里把两者并成一行，是为了让这一行能原地从「正在调用」变成「拿到结果」——
 * 而不是上下长出两行、把下面的内容整体挤开。
 *
 * 配对不能只按工具名：模型提出的是意图名（hospital.search），实际执行的是映射后的
 * 工具名（catalog.queryHospitals），两者本来就不一样。只按名字配对的话，这一步会永远
 * 停在「调用中」。所以先按名字找，找不到就认领最早那个还没结果的步骤——工具是按提出
 * 顺序执行的，这个兜底对着真实顺序。
 *
 * 反过来，结果也可能比提案多：模型只提一步（「找这家医院」），后端按既定流程把后续
 * 几步一起查完。这些调用确实发生过，必须各占一行，不能因为没有提案就被丢掉。
 */
export function toSteps(events: ProgressEvent[]): { steps: Step[]; status: string } {
  const steps: Step[] = [];
  let status = '';
  const claim = (tool: string) => {
    const unpaired = steps.filter(step => step.executedTool === null);
    return unpaired.find(step => step.proposedTool === tool) ?? unpaired[0] ?? null;
  };
  for (const event of events) {
    if (event.kind === 'TOOL_PROPOSED' && event.tool) {
      steps.push({
        key: event.seq, proposedTool: event.tool, executedTool: null,
        parameters: event.parameters, result: null, success: null,
      });
      continue;
    }
    if (event.kind === 'TOOL_RESULT' && event.tool) {
      const target = claim(event.tool);
      if (target) {
        target.executedTool = event.tool;
        target.result = event.result;
        target.success = event.success;
      } else {
        steps.push({
          key: event.seq, proposedTool: event.tool, executedTool: event.tool,
          parameters: event.parameters, result: event.result, success: event.success,
        });
      }
      continue;
    }
    // 思考类事件不单独占一行，只更新「现在在干什么」。
    status = event.text ?? status;
  }
  return { steps, status };
}
