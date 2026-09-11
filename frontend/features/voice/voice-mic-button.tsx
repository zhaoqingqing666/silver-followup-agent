'use client';

import { useEffect, useRef, useState } from 'react';
import { Mic } from 'lucide-react';
import { useVoiceInput } from './use-voice-input';

/**
 * 按住说话的麦克风按钮，助手页输入条与全局悬浮入口共用。
 * 未传 onNotice 时按钮自己弹一条提示，避免“按了没反应”。
 */
export function VoiceMicButton({ onTranscript, onNotice, disabled = false, variant = 'inline' }: {
  onTranscript: (text: string) => void;
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
    timer.current = window.setTimeout(() => setNotice(''), 5000);
  };

  const { listening, start, stop } = useVoiceInput({ onTranscript, onNotice: showNotice });

  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current);
  }, []);

  const tone = listening
    ? 'bg-red-100 text-red-700'
    : floating ? 'bg-primary text-white shadow-[0_8px_24px_rgb(91_55_32/35%)]' : 'bg-secondary text-primary';

  return <div className={floating ? 'relative' : 'contents'}>
    {floating && (listening || notice) && <output
      className={`absolute bottom-[76px] left-1/2 block -translate-x-1/2 rounded-2xl bg-white px-4 py-2 text-center text-base font-bold text-primary shadow-lg ${notice ? 'w-[280px] text-sm text-[#6c3d24]' : 'whitespace-nowrap'}`}>
      {notice || '正在听…'}
    </output>}
    <button
      type="button"
      aria-label={listening ? '正在听，松开结束' : '按住说话'}
      aria-pressed={listening}
      disabled={disabled}
      onPointerDown={start}
      onPointerUp={stop}
      onPointerCancel={stop}
      onPointerLeave={event => { if (event.pointerType === 'mouse') stop(); }}
      className={floating
        ? `grid size-16 touch-none select-none place-items-center rounded-full border-4 border-[#fffaf3] transition active:scale-95 disabled:opacity-50 ${tone}`
        : `flex min-h-12 shrink-0 touch-none select-none items-center gap-2 rounded-2xl px-3 font-bold transition active:scale-95 disabled:opacity-50 ${tone}`}
    >
      <Mic className={floating ? 'size-8' : 'size-6'} aria-hidden="true" />
      {!floating && <span className="hidden min-[380px]:inline">{listening ? '正在听…' : '按住说话'}</span>}
    </button>
  </div>;
}
