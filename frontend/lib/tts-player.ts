/**
 * 全局朗读播放器：本地语音（SpeechSynthesis）与云端音频（HTMLAudioElement）
 * 统一由一个协调器管理，保证同一时间只会有一段声音在播，防止老人连点叠音。
 *
 * 降级链（点朗读时）：
 *   本地 SpeechSynthesis 可用（含中文语音） → 本地直接播放（不消耗云端额度）
 *   否则 → 走云端 /api/tts/synthesize（保留原接口作为 fallback）
 *
 * 同一内容再次触发：正在播放 → 停止（toggle 语义），不会重新播。
 */
import { LocalSpeechService } from './local-speech';
import { synthesizeSpeech } from './asr-tts-api';

type Listener = (playing: boolean, src: string | null) => void;

const listeners = new Set<Listener>();

/** 当前正在朗读内容的唯一标识；null 表示没有在播 */
let currentKey: string | null = null;
/** 云端音频播放（与本地语音互斥） */
let currentAudio: HTMLAudioElement | null = null;
/** 本地语音当前 utterance（与云端互斥） */
let currentUtterance: SpeechSynthesisUtterance | null = null;
let speechStarted = false;

function emit(playing: boolean): void {
  listeners.forEach(fn => fn(playing, currentKey));
}

function isCloudPlaying(): boolean {
  return currentAudio !== null && !currentAudio.paused && !currentAudio.ended;
}

function isActive(): boolean {
  return currentAudio !== null || speechStarted;
}

function releaseAudio(): void {
  if (!currentAudio) return;
  const audio = currentAudio;
  currentAudio = null;
  audio.onended = null;
  audio.onerror = null;
  try { audio.pause(); } catch { /* 忽略 */ }
}

function releaseSpeech(): void {
  if (!currentUtterance && !speechStarted) return;
  currentUtterance = null;
  speechStarted = false;
  LocalSpeechService.cancel();
}

/** 读取用户 TTS 设置（音色/语速，localStorage 持久化） */
export function loadTtsSettings(): { voice: string; speed: number } {
  try {
    const voice = window.localStorage.getItem('silver-agent-tts-voice') || 'Cherry';
    const speed = parseFloat(window.localStorage.getItem('silver-agent-tts-speed') || '1.0');
    return { voice, speed: isNaN(speed) ? 1.0 : speed };
  } catch {
    return { voice: 'Cherry', speed: 1.0 };
  }
}

/** 订阅全局播放状态变化，返回取消订阅函数 */
export function onPlaybackChange(fn: Listener): () => void {
  listeners.add(fn);
  return () => { listeners.delete(fn); };
}

/** 停止当前播放（本地语音或云端音频都停） */
export function stopPlayback(): void {
  if (currentAudio === null && !speechStarted && currentKey === null) return;
  const shouldEmit = currentAudio !== null || speechStarted || currentKey !== null;
  releaseAudio();
  releaseSpeech();
  if (shouldEmit) {
    currentKey = null;
    emit(false);
  }
}

/** 本地语音读完整段（或出错）时的收尾；事件目标必须仍是当前 utterance，避免误清新播放 */
function finishSpeechIfCurrent(target: EventTarget | null): void {
  if (!speechStarted || !currentUtterance) return;
  if (target !== currentUtterance) return;
  speechStarted = false;
  currentUtterance = null;
  currentKey = null;
  emit(false);
}

/** 用本地语音朗读。key 是这条内容的唯一标识。开始成功返回 true。 */
function beginLocal(key: string, text: string): boolean {
  if (!LocalSpeechService.isAvailable() || !text?.trim()) return false;
  const same = currentKey === key && speechStarted;
  stopPlayback();
  if (same) return false;
  const utterance = LocalSpeechService.speak(text, { rate: loadTtsSettings().speed });
  if (!utterance) return false;
  currentKey = key;
  currentUtterance = utterance;
  speechStarted = true;
  utterance.onend = event => finishSpeechIfCurrent(event.target);
  utterance.onerror = event => finishSpeechIfCurrent(event.target);
  emit(true);
  return true;
}

/** 用云端音频播放。key 是这条内容的唯一标识。开始成功返回 true。 */
function beginCloud(key: string, url: string): boolean {
  if (!url) return false;
  const same = currentKey === key && isCloudPlaying();
  stopPlayback();
  if (same) return false;
  const audio = new Audio(url);
  const done = () => {
    if (currentAudio !== audio) return;
    currentAudio = null;
    currentKey = null;
    emit(false);
  };
  currentKey = key;
  currentAudio = audio;
  audio.onended = done;
  audio.onerror = done;
  audio.play().catch(done);
  emit(true);
  return true;
}

/**
 * 朗读前去掉 Markdown 格式标记（**加粗**、## 标题、`代码`、*斜体*、~~删除线~~ 等），
 * 只保留正文，不改变数字、单位、药品名称等内容，避免把星号/井号念出来。
 */
function stripMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, (block) => block.replace(/```[a-zA-Z]*\n?|\n?```/g, '').trim())
    .replace(/`([^`]+)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/_([^_]+)_/g, '$1')
    .replace(/~~([^~]+)~~/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '')
    .replace(/^\s{0,3}>\s?/gm, '');
}

/**
 * 朗读一段文字（推荐入口）。
 * 已在播放同一条内容 → 停止并返回 false；
 * 本地 SpeechSynthesis 可用 → 本地播放；
 * 否则回退到云端 TTS。
 */
export async function speakText(key: string, text: string): Promise<boolean> {
  if (!text?.trim()) return false;
  if (isActive() && currentKey === key) {
    stopPlayback();
    return false;
  }
  text = stripMarkdown(text);
  if (beginLocal(key, text)) return true;
  // 云端回退（保留 /api/tts/synthesize 作为 fallback）
  const { voice, speed } = loadTtsSettings();
  const url = await synthesizeSpeech(text, { voice, speed });
  if (!url) return false;
  return beginCloud(key, url);
}
