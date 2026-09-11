'use client';

import { useEffect, useRef, useState } from 'react';
import { Mic, ArrowUp } from 'lucide-react';
import { LevelMeter } from './level-meter';
import { usePressToTalk, type VoiceRecording } from './use-press-to-talk';

/**
 * 全局悬浮的「按住说话」按钮（`app/page.tsx` 固定在底部中央，各页共用）。
 *
 * 屏上的音量动画**只有浮层里那一条**，按钮自己不放第二条：
 * 老人的手指正压在这个 64px 的圆上，按钮里的条子大半被指头挡住，
 * 露出来的那截还要和浮层那条抢注意力；两条各写一套尺寸，尺寸也各自跑偏
 * （内圆只有 56px，默认那套 5 根条要 64px，靠 `scale-90` 硬缩还压着白圈）。
 * 所以按钮只保留图标与配色变化——按住时变红、取消预备时变琥珀色——动画交给浮层。
 */
export function VoiceMicButton({ onTranscript, onNotice, disabled = false }: {
  /** 说完一句话：识别出的文字 + 可回放的原话（没录上时为 null）。
   *  文字为空表示没听清，但录音还在——调用方据此留一条能回放的语音，别把老人的原话丢掉。 */
  onTranscript: (text: string, recording: VoiceRecording | null) => void;
  onNotice?: (message: string) => void;
  disabled?: boolean;
}) {
  const [notice, setNotice] = useState('');
  const timer = useRef<number | null>(null);

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
      : 'bg-primary text-white shadow-[0_8px_24px_rgb(91_55_32/35%)]';

  // 悬浮按钮上方那一条提示：优先显示后端的提示，其次显示实时字幕。
  // 按住期间不再重复显示——那段时间由下面的大浮层负责说话。
  const floatingCaption = notice
    || (!holding && (interimText ? `“${interimText}”` : transcribing ? '正在识别…' : ''));

  return <div className="relative">
    {/* 录音时的大浮层：老人手指按在按钮上，视线却在屏幕中间，
        所以音量、字幕、松开后的结果都要放大到一眼能看清。
        这是全屏唯一的音量动画——按钮里不再放第二条。
        pointer-events-none 是必须的——手指还在按钮上拖着，浮层不能拦住指针事件。 */}
    {holding && <div
      aria-live="polite"
      // 宽度按视口算（100vw），不按包含块算：祖先一旦带上 transform，fixed 的包含块
      // 就变成那个几十像素宽的小盒子，写成百分比会算出一个放不下一个汉字的宽度。
      className={`pointer-events-none fixed bottom-[132px] left-1/2 z-50 w-[calc(100vw-40px)] max-w-[420px] -translate-x-1/2 rounded-3xl px-6 py-5 text-center shadow-[0_16px_48px_rgb(91_55_32/28%)] ${cancelArmed ? 'bg-[#fff3cd]' : 'bg-white'}`}>
      <p className={`text-xl font-bold ${cancelArmed ? 'text-[#8a6d3b]' : 'text-red-700'}`}>
        {cancelArmed ? '松开手指，取消发送' : '正在听您说话…'}
      </p>
      <div className="mt-3 flex justify-center">
        <LevelMeter stream={stream} active
          barClass={`w-2 rounded-full ${cancelArmed ? 'bg-[#d9a93f]' : 'bg-red-500'}`} />
      </div>
      {interimText && !cancelArmed && <p className="mt-3 min-h-7 break-words text-lg font-semibold leading-7 text-[#6c3d24]">“{interimText}”</p>}
      <p className={`mt-2 flex items-center justify-center gap-1 text-base font-semibold ${cancelArmed ? 'text-[#8a6d3b]' : 'text-muted-foreground'}`}>
        {cancelArmed
          ? <>滑回来还能继续说</>
          : <><ArrowUp className="size-4" aria-hidden="true" />松开手指就发送，上滑取消</>}
      </p>
    </div>}

    {!holding && floatingCaption && <output
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
      className={`grid size-16 touch-none select-none place-items-center rounded-full border-4 border-[#fffaf3] transition active:scale-95 disabled:opacity-50 ${tone}`}
    >
      <Mic className="size-8" aria-hidden="true" />
    </button>
  </div>;
}
