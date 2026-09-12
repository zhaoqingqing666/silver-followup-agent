'use client';

import { useEffect, useRef, useState } from 'react';
import { LoaderCircle, Play, Volume2, VolumeX } from 'lucide-react';
import { onPlaybackChange, speakText, stopPlayback } from '@/lib/tts-player';
import type { ChatMessage } from '@/types/domain';

/** 录音时长显示：只认有效秒数，拿不到就不显示，
 *  免得 MediaRecorder 的 WebM 报 Infinity 时给老人看到 "Infinitys" 这种字 */
function formatDuration(seconds?: number): string {
  return typeof seconds === 'number' && Number.isFinite(seconds) && seconds > 0 ? `${seconds.toFixed(1)}s` : '';
}

/** 语音消息气泡：点击播放/暂停，像微信一样。
 *  只播放录音本身——audioUrl 直接指向 MediaRecorder 产出的原始 Blob，
 *  不经过 ASR、不调用 TTS、也不把识别文字重新合成语音：
 *  老人点一下，听到的必须是自己刚才说的原话。 */
function VoiceBubble({ message }: { message: ChatMessage }) {
  const [playing, setPlaying] = useState(false);
  /** 浏览器确实放不出这段录音时明说，而不是留一个"点了没动静"的死气泡 */
  const [failed, setFailed] = useState(false);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  useEffect(() => () => { audioRef.current?.pause(); }, []);

  const togglePlay = () => {
    const url = message.audioUrl;
    if (!url) return;
    let audio = audioRef.current;
    if (!audio) {
      const element = new Audio(url);
      // 播完 / 播放出错都要让按钮回到"语音消息"，不能卡在"播放中…"
      element.onended = () => setPlaying(false);
      element.onerror = () => { setPlaying(false); setFailed(true); };
      audioRef.current = element;
      audio = element;
    }
    if (playing) { audio.pause(); setPlaying(false); return; }
    // 同一时刻只留一路声音：先停掉正在朗读的助手回复
    stopPlayback();
    setFailed(false);
    // 不预先写 currentTime：新元素本来就从 0 播起，播完再点会自动回到开头；
    // 而在媒体元数据就绪前写 currentTime，部分浏览器会直接抛错，点击就变成"没反应"。
    audio.play().then(() => setPlaying(true)).catch(() => { setPlaying(false); setFailed(true); });
  };

  // 时长只影响气泡宽度；拿不到有效秒数时按 3 秒估，别让宽度算出 Infinity
  const safeDuration = typeof message.audioDuration === 'number' && Number.isFinite(message.audioDuration) && message.audioDuration > 0
    ? message.audioDuration
    : 3;
  const widthPx = Math.min(280, Math.max(120, 80 + safeDuration * 8));

  return <>
    <button type="button" onClick={togglePlay} aria-label={playing ? '停止播放语音' : '播放语音'}
      style={{ width: widthPx }}
      className="flex min-h-12 items-center justify-between gap-2 rounded-2xl bg-primary px-4 text-primary-foreground">
      <span className="flex items-center gap-2">
        {playing ? <Volume2 className="size-5" /> : <Play className="size-5" />}
        <span className="text-sm">{playing ? '播放中…' : '语音消息'}</span>
      </span>
      <span className="text-xs opacity-70">{formatDuration(message.audioDuration)}</span>
    </button>
    {/* 放不出来时说清楚原因，并把这条语音的内容露出来，别让老人对着一个没反应的按钮反复点 */}
    {failed && <p className="text-xs leading-5 opacity-90">
      这段录音没能播放出来。{message.text ? `您刚才说的是："${message.text}"` : ''}
    </p>}
  </>;
}

export function ChatBubble({ message }: { message: ChatMessage }) {
  const [playing, setPlaying] = useState(false);
  const [loading, setLoading] = useState(false);
  // 这条消息在全局播放器里的唯一标识：播放状态变化时据此判断"是不是本气泡在播"
  const keyRef = useRef<string | null>(null);

  useEffect(() => onPlaybackChange((isPlaying, src) => {
    setPlaying(isPlaying && src !== null && src === keyRef.current);
  }), []);

  const speak = async () => {
    // 助手回复要能念；用户自己发的语音消息（没有录音文件时只剩文字）也要能点着念
    if (!message.text) return;
    if (message.role !== 'assistant' && !message.isVoice) return;
    if (playing) { stopPlayback(); return; }
    const key = `msg:${message.id}`;
    keyRef.current = key;
    setLoading(true);
    try {
      // 本地优先：浏览器中文语音直接播；不支持/没中文语音才回退云端
      await speakText(key, message.text);
    } finally { setLoading(false); }
  };

  return <div className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}>
    <div className={`max-w-[88%] space-y-2 rounded-3xl px-4 py-3 text-[17px] leading-7 ${message.role === 'user'
      ? 'rounded-br-md bg-primary text-primary-foreground'
      : 'rounded-bl-md bg-card shadow-sm ring-1 ring-border'}`}>
      {message.imageDataUrls?.length ? <div className="space-y-2">
        {message.imageDataUrls.map((url, index) => (
          // 老人当场拍/选的图，data URL 直接显示即可，不需要打包器的图片优化
          // eslint-disable-next-line next/no-img-element
          <img key={index} src={url} alt={`上传的图片${index + 1}`} className="max-h-48 w-full max-w-xs rounded-xl object-cover" />
        ))}
      </div> : null}

      {/* 用户语音消息气泡：上面是可复听的语音条（点一下听自己刚才说的原话），下面是识别出的文字。
          文字故意不做成按钮——点它不会再触发朗读，以免和"回放原话"混在一起。 */}
      {message.role === 'user' && message.audioUrl && <>
        <VoiceBubble message={message} />
        {message.text && <p className="flex items-start gap-1.5">
          <span className="shrink-0 text-base leading-6" title="语音识别">🎤</span><span>{message.text}</span>
        </p>}
        {message.voiceState === 'transcribing' && <p className="flex items-center gap-1.5 text-xs opacity-70">
          <LoaderCircle className="size-3 animate-spin" />正在识别…
        </p>}
        {message.voiceState === 'error' && <p className="text-xs opacity-70">语音识别失败，请重试</p>}
      </>}

      {/* 普通用户消息（打字） */}
      {message.role === 'user' && !message.audioUrl && message.text && (
        message.isVoice ? (
          // 没有录音文件可回放的语音消息（浏览器兜底识别）：点一下也能朗读内容
          <button type="button" onClick={() => void speak()} aria-label={playing ? '停止朗读' : '朗读这条语音'} className="flex items-start gap-1.5 text-left">
            <span className="shrink-0 text-base leading-6" title="语音识别">{playing ? '🔊' : '🎤'}</span>
            <span>{message.text}</span>
          </button>
        ) : <p className="flex items-start gap-1.5"><span>{message.text}</span></p>
      )}

      {/* 助手回复 */}
      {message.role === 'assistant' && message.text && <>
        {message.isVoice && <p className="text-xs opacity-70">🎤 来自语音输入</p>}
        {/* whitespace-pre-line：模型回复里的换行（"重要信息单独成行"）要真的换行显示，而不是挤成一行 */}
        <p className="whitespace-pre-line">{message.text}</p>
      </>}

      {message.role === 'assistant' && message.text && <button onClick={() => void speak()} disabled={loading}
        aria-label={playing ? '停止朗读' : '朗读这条回复'}
        className="mt-1 inline-flex size-9 items-center justify-center rounded-full border border-[#e8c7a4] bg-white text-primary shadow-sm">
        {loading ? <LoaderCircle className="size-5 animate-spin" /> : playing ? <VolumeX className="size-5" /> : <Volume2 className="size-5" />}
      </button>}
    </div>
  </div>;
}
