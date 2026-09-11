'use client';

import { useEffect, useRef, useState } from 'react';
import { Mic, ArrowUp } from 'lucide-react';
import { LevelMeter } from './level-meter';
import { usePressToTalk, type VoiceRecording } from './use-press-to-talk';

/**
 * 按住说话的麦克风按钮，助手页输入条与全局悬浮入口共用。
 * 未传 onNotice 时按钮自己弹一条提示，避免“按了没反应”。
 */
export function VoiceMicButton({ onTranscript, onNotice, disabled = false, variant = 'inline' }: {
  /** 说完一句话：识别出的文字 + 可回放的原话（没录上时为 null）。
   *  文字为空表示没听清，但录音还在——调用方据此留一条能回放的语音，别把老人的原话丢掉。 */
  onTranscript: (text: string, recording: VoiceRecording | null) => void;
  onNotice?: (message: string) => void;
  disabled?: boolean;
  variant?: 'inline' | 'floating';
}) {
  const [notice, setNotice] = useState('');
  const timer = useRef<number | null>(null);
  const floating = variant === 'floating';

  const showNotice = (message: string) => {
    if (onNotice) {
      onNotice(message);
      return;
    }
    setNotice(message);
    if (timer.current) window.clearTimeout(timer.current);
    if (!message) return;
    timer.current = window.setTimeout(() => setNotice(''), 5000);
  };

  const talk = usePressToTalk({
    onResult: outcome => {
      if (!outcome.text && !outcome.recording) return;
      onTranscript(outcome.text, outcome.recording);
    },
    onNotice: showNotice,
  });
  const { phase, holding, cancelArmed, interimText, stream, cancel } = talk;

  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current);
  }, []);

  // 切页 / 卸载时把进行中的录音和识别一并丢掉，别让麦克风一直亮着
  useEffect(() => () => cancel(), [cancel]);

  const transcribing = phase === 'transcribing';
  const tone = cancelArmed
    ? 'bg-[#fff3cd] text-[#8a6d3b]'
    : holding
      ? 'bg-red-100 text-red-700'
      : floating ? 'bg-primary text-white shadow-[0_8px_24px_rgb(91_55_32/35%)]' : 'bg-secondary text-primary';

  // 悬浮按钮上方那一条提示：优先显示后端的提示，其次显示实时字幕。
  // 按住期间不再重复显示——那段时间由下面的大浮层负责说话。
  const floatingCaption = notice
    || (!holding && (interimText ? `“${interimText}”` : transcribing ? '正在识别…' : ''));

  const label = cancelArmed ? '松开取消' : holding ? '松开发送' : transcribing ? '正在识别…' : '按住说话';

  return <div className={floating ? 'relative' : 'contents'}>
    {/* 录音时的大浮层：老人手指按在按钮上，视线却在屏幕中间，
        所以音量、字幕、松开后的结果都要放大到一眼能看清。
        pointer-events-none 是必须的——手指还在按钮上拖着，浮层不能拦住指针事件。 */}
    {floating && holding && <div
      aria-live="polite"
      className={`pointer-events-none fixed bottom-[132px] left-1/2 z-50 w-[calc(100%-40px)] max-w-[420px] -translate-x-1/2 rounded-3xl px-6 py-5 text-center shadow-[0_16px_48px_rgb(91_55_32/28%)] ${cancelArmed ? 'bg-[#fff3cd]' : 'bg-white'}`}>
      <p className={`text-xl font-bold ${cancelArmed ? 'text-[#8a6d3b]' : 'text-red-700'}`}>
        {cancelArmed ? '松开手指，取消发送' : '正在听您说话…'}
      </p>
      <div className="mt-3 flex justify-center">
        <LevelMeter stream={stream} active bars={9} minBarPx={14} maxBarPx={72} heightClass="h-20"
          barClass={`w-2 rounded-full ${cancelArmed ? 'bg-[#d9a93f]' : 'bg-red-500'}`} />
      </div>
      {interimText && !cancelArmed && <p className="mt-3 min-h-7 break-words text-lg font-semibold leading-7 text-[#6c3d24]">“{interimText}”</p>}
      <p className={`mt-2 flex items-center justify-center gap-1 text-base font-semibold ${cancelArmed ? 'text-[#8a6d3b]' : 'text-muted-foreground'}`}>
        {cancelArmed
          ? <>滑回来还能继续说</>
          : <><ArrowUp className="size-4" aria-hidden="true" />松开手指就发送，上滑取消</>}
      </p>
    </div>}

    {floating && !holding && floatingCaption && <output
      className={`absolute bottom-[76px] left-1/2 block -translate-x-1/2 rounded-2xl bg-white px-4 py-2 text-center text-base font-bold text-primary shadow-lg ${notice ? 'w-[280px] text-sm text-[#6c3d24]' : 'whitespace-nowrap'}`}>
      {floatingCaption}
    </output>}
    <button
      type="button"
      aria-label={holding ? (cancelArmed ? '松开取消发送' : '松开发送') : '按住说话'}
      aria-pressed={holding}
      disabled={disabled || transcribing}
      onPointerDown={talk.onPointerDown}
      onPointerMove={talk.onPointerMove}
      onPointerUp={talk.onPointerUp}
      onPointerCancel={talk.onPointerCancel}
      onPointerLeave={event => { if (event.pointerType === 'mouse' && holding) talk.onPointerUp(); }}
      className={floating
        ? `grid size-16 touch-none select-none place-items-center rounded-full border-4 border-[#fffaf3] transition active:scale-95 disabled:opacity-50 ${tone}`
        : `flex min-h-12 shrink-0 touch-none select-none items-center gap-2 rounded-2xl px-3 font-bold transition active:scale-95 disabled:opacity-50 ${tone}`}
    >
      {floating
        ? (holding ? <LevelMeter stream={stream} active className="scale-90" /> : <Mic className="size-8" aria-hidden="true" />)
        : <>
          <Mic className="size-6" aria-hidden="true" />
          {/* 按住期间文字要留着：颜色和音量条都不足以说清“松开发送 / 松开取消” */}
          <span className="hidden min-[380px]:inline">{label}</span>
          {holding && <LevelMeter stream={stream} active heightClass="h-6" />}
        </>}
    </button>
  </div>;
}
