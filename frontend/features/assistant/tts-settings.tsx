'use client';

import { useEffect, useState } from 'react';
import { ChevronDown, Gauge, Play, RotateCcw, Volume2 } from 'lucide-react';
import { getVoices } from '@/lib/asr-tts-api';
import {
  DEFAULT_TTS_SPEED, DEFAULT_TTS_VOICE, loadTtsSettings, saveTtsSettings, speakText, stopPlayback,
} from '@/lib/tts-player';
import type { VoicePreference } from '@/types/domain';

/** 语速滑条的取值范围与步长：0.5 太慢、2.0 太快，都是实测能听清的边界。 */
const RATE_MIN = 0.5;
const RATE_MAX = 2;
const RATE_STEP = 0.1;

/** 试听用的固定句子。每次换 key 是为了「再点一次＝重新念」，而不是变成「停」。 */
const SAMPLE = '您好，我是复诊助手。这是当前的朗读效果。';

const clampRate = (value: number) =>
  Math.round(Math.min(RATE_MAX, Math.max(RATE_MIN, value)) * 10) / 10;

/**
 * 助手页的朗读设置：音色与语速。
 *
 * 两个设置存在两处，是有意为之：
 * - 语速的权威来源是后端偏好（每条朗读调用都带 voicePreference.speechRate），
 *   所以拖动滑条要真的写回后端，否则「改完没效果」。
 * - 音色只有本地一份（后端偏好表没有这个字段），只影响云端合成，浏览器自带朗读用不上。
 *
 * 老人只应该看到一个旋钮，不存在「本地改一个、后端改一个」的选择。
 */
export function TtsSettings({ voicePreference, busy, error, onChange }: {
  voicePreference: VoicePreference;
  /** 保存中：滑条和按钮一起禁用，避免连拖出多个并发请求互相覆盖 */
  busy: boolean;
  error: string;
  onChange: (patch: { speechRate?: number }) => void;
}) {
  const [open, setOpen] = useState(false);
  const [voices, setVoices] = useState<Array<{ id: string; label: string }>>([]);
  // 音色只有 localStorage 一份，惰性读一次即可；后端偏好里没有这个字段，不需要同步。
  const [voice, setVoice] = useState(() => loadTtsSettings().voice);
  /**
   * 拖动过程中的临时值。只在事件回调里写，不做 prop→state 的镜像同步：
   * 保存成功时 prop 会变成同一个数，清掉临时值即可；保存失败时 prop 回滚成旧值，
   * 清掉临时值就是「滑条老老实实退回真实值」，不需要额外的补偿逻辑。
   */
  const [draggingRate, setDraggingRate] = useState<number | null>(null);
  const rate = draggingRate ?? voicePreference.speechRate;

  useEffect(() => {
    let cancelled = false;
    // 音色列表来自后端 /api/tts/voices。取不到（没配 key / 后端没起）就是云端朗读没开，
    // 这时不报错、也不假装能选，页面会明确告诉老人「会用手机自带的语音」。
    void getVoices().then(list => {
      if (cancelled || !list.length) return;
      setVoices(list);
      // 存着的音色已经不在可用列表里（换了 key、音色下线）→ 自动改选第一个可用的。
      // 不这么做的话，老人会卡在「点喇叭没声音」，且完全看不出原因。
      if (!list.some(item => item.id === loadTtsSettings().voice)) {
        setVoice(list[0].id);
        saveTtsSettings({ voice: list[0].id });
      }
    });
    return () => { cancelled = true; };
  }, []);

  const commitRate = () => {
    const rounded = clampRate(rate);
    setDraggingRate(null);
    // 本地兜底 + 写回后端偏好，两处一起更新，避免只改一半。
    saveTtsSettings({ speed: rounded });
    if (rounded !== voicePreference.speechRate) onChange({ speechRate: rounded });
  };

  const pickVoice = (id: string) => {
    setVoice(id);
    saveTtsSettings({ voice: id });
  };

  const preview = () => {
    stopPlayback();
    void speakText(`tts-preview-${Date.now()}`, SAMPLE, { rate });
  };

  const reset = () => {
    pickVoice(voices[0]?.id ?? DEFAULT_TTS_VOICE);
    setDraggingRate(DEFAULT_TTS_SPEED);
    saveTtsSettings({ speed: DEFAULT_TTS_SPEED });
    if (DEFAULT_TTS_SPEED !== voicePreference.speechRate) onChange({ speechRate: DEFAULT_TTS_SPEED });
  };

  return <details open={open} onToggle={event => setOpen((event.currentTarget as HTMLDetailsElement).open)}
    className="rounded-2xl border border-[#e6bc8c] bg-white px-4 py-3 shadow-sm">
    <summary className="flex cursor-pointer list-none items-center gap-2 text-base font-bold text-[#6c3d24]">
      <Volume2 className="size-5 text-primary" aria-hidden="true" />朗读设置
      <span className="ml-auto rounded-full bg-[#fff0dc] px-2.5 py-0.5 text-sm font-bold text-primary">{rate.toFixed(1)}×</span>
      <ChevronDown className={`size-4 transition ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
    </summary>

    <div className="mt-3 space-y-4">
      <div>
        <label htmlFor="tts-voice" className="text-sm font-bold text-[#6c3d24]">朗读音色</label>
        {voices.length > 0
          ? <select id="tts-voice" value={voice} disabled={busy}
            onChange={event => pickVoice(event.target.value)}
            className="mt-1.5 min-h-12 w-full rounded-xl border border-[#dfb98f] bg-white px-3 text-base font-semibold text-[#6c3d24] disabled:opacity-50">
            {voices.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
          </select>
          : <p className="mt-1.5 rounded-xl bg-[#fff8ed] px-3 py-2 text-sm leading-6 text-[#6c3d24]">
            云端朗读没有开启，音色暂时不能选。这时会用手机自带的语音念给您听，能正常朗读。
          </p>}
      </div>

      <div>
        <label htmlFor="tts-rate" className="flex items-center gap-2 text-sm font-bold text-[#6c3d24]">
          <Gauge className="size-4 text-primary" aria-hidden="true" />语速
          <span className="ml-auto text-base">{rate.toFixed(1)}×</span>
        </label>
        <input id="tts-rate" type="range" min={RATE_MIN} max={RATE_MAX} step={RATE_STEP}
          value={rate} disabled={busy}
          aria-valuetext={`${rate.toFixed(1)}倍速`}
          onChange={event => setDraggingRate(Number(event.target.value))}
          // 只在松手 / 键盘操作结束 / 失焦时才写回后端：拖一次滑条会连发几十个 change，
          // 每个都发一次 PUT 既浪费又会让状态互相覆盖。
          onPointerUp={commitRate}
          onKeyUp={commitRate}
          onBlur={commitRate}
          className="mt-2 h-2 w-full accent-[#8a5a3b] disabled:opacity-50" />
        <div className="mt-1 flex justify-between text-sm text-muted-foreground" aria-hidden="true">
          <span>慢</span><span>正常</span><span>快</span>
        </div>
      </div>

      <div className="flex gap-2">
        <button type="button" onClick={preview} disabled={busy}
          className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl border border-[#dfb98f] bg-white text-base font-bold text-[#6c3d24] disabled:opacity-50">
          <Play className="size-5" aria-hidden="true" />试听
        </button>
        <button type="button" onClick={reset} disabled={busy}
          className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl border border-[#dfb98f] bg-white text-base font-bold text-[#6c3d24] disabled:opacity-50">
          <RotateCcw className="size-5" aria-hidden="true" />恢复默认
        </button>
      </div>

      {busy && <p className="text-sm text-muted-foreground">正在保存…</p>}
      {error && <p className="text-sm font-semibold text-red-700">{error}</p>}
    </div>
  </details>;
}
