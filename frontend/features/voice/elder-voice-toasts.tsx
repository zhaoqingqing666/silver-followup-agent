'use client';

import { AlertCircle, X } from 'lucide-react';
import { useElderVoice } from './elder-voice-provider';

/**
 * 全局提示条。
 *
 * <p>老人端任何页面说的话都走同一套发送，所以「提示」和「出错」也不能只有助手页看得见：
 * 他在首页按着麦克风说话，回复落在助手页，但「这一段已经结束，已为您开了一段新的」
 * 和「暂时连不上」必须当场出现在他眼前。
 */
export function ElderVoiceToasts() {
  const { hint, error, dismissError } = useElderVoice();

  return <>
    {hint && <div className="fixed bottom-[170px] left-1/2 z-50 w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 rounded-2xl bg-black/80 px-4 py-2.5 text-center text-sm leading-6 text-white shadow-lg">{hint}</div>}

    {/* 失败要说清是失败：老人这一轮没办成，界面不能装作办成了，也不能拿上一轮的
        页面指令把他带到别的地方去。 */}
    {error && <div role="alert" className="fixed bottom-[170px] left-1/2 z-50 flex w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 items-start gap-2 rounded-2xl bg-[#7a2f28] px-4 py-3 text-left text-sm leading-6 text-white shadow-lg">
      <AlertCircle className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <p className="min-w-0 flex-1">{error}</p>
      <button type="button" onClick={dismissError} aria-label="关闭提示" className="grid size-7 shrink-0 place-items-center rounded-full bg-white/20">
        <X className="size-4" />
      </button>
    </div>}
  </>;
}
