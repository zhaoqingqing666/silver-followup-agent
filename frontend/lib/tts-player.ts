/**
 * 全局朗读播放器：本地语音（SpeechSynthesis）与云端音频（HTMLAudioElement）
 * 统一由一个协调器管理，保证同一时间只会有一段声音在播，防止老人连点叠音。
 *
 * 降级链（点朗读时）：
 *   本地 SpeechSynthesis 可用（含中文语音） → 本地直接播放（不消耗云端额度）
 *   否则 → 走云端 /api/tts/synthesize（保留原接口作为 fallback）
 *
 * 同一 key 再次触发：正在播放 → 停止（toggle 语义），不会重新播。
 * 「念一遍」这类单向按钮不要复用同一 key —— 再点会变成停，而不是重新念。
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

/**
 * 默认朗读音色（云端）与语速。
 * 音色只认百炼的音色 id，换了一把 key、音色列表变了的话，设置面板会自动改选列表里第一个可用的，
 * 不能让「Cherry」这种写死的值在换 key 之后把老人卡在「点了喇叭没声音」上。
 */
export const DEFAULT_TTS_VOICE = 'Cherry';
export const DEFAULT_TTS_SPEED = 1.0;

/** 读取用户 TTS 设置（音色/语速，localStorage 持久化） */
export function loadTtsSettings(): { voice: string; speed: number } {
  try {
    const voice = window.localStorage.getItem('silver-agent-tts-voice') || DEFAULT_TTS_VOICE;
    const speed = parseFloat(window.localStorage.getItem('silver-agent-tts-speed') || String(DEFAULT_TTS_SPEED));
    return { voice, speed: isNaN(speed) ? DEFAULT_TTS_SPEED : speed };
  } catch {
    return { voice: DEFAULT_TTS_VOICE, speed: DEFAULT_TTS_SPEED };
  }
}

/**
 * 写入用户 TTS 设置（部分更新）。
 *
 * 语速的权威来源是后端偏好（每条 speakText 调用都带 voicePreference.speechRate），
 * 这里同步一份只是为了「偏好还没加载出来 / 请求失败」时不至于退回默认值。
 * 音色则只有本地这一份——后端偏好表里没有音色字段。
 */
export function saveTtsSettings(next: { voice?: string; speed?: number }): void {
  try {
    if (next.voice != null) window.localStorage.setItem('silver-agent-tts-voice', next.voice);
    if (next.speed != null) window.localStorage.setItem('silver-agent-tts-speed', String(next.speed));
  } catch { /* 隐私模式下写不进去，也不该让设置面板崩掉 */ }
}

/**
 * 订阅全局播放状态变化，返回取消订阅函数。
 * 订阅时会先用当前状态回调一次：气泡是列表里新挂载的，不先同步一次就不知道自己正在播。
 */
export function onPlaybackChange(fn: Listener): () => void {
  listeners.add(fn);
  fn(isActive(), currentKey);
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
function beginLocal(key: string, text: string, options: PlaybackOptions): boolean {
  if (!LocalSpeechService.isAvailable() || !text?.trim()) return false;
  const same = currentKey === key && speechStarted;
  stopPlayback();
  if (same) return false;
  const utterance = LocalSpeechService.speak(text, {
    rate: options.rate ?? loadTtsSettings().speed,
    volume: options.volume,
  });
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
function beginCloud(key: string, url: string, options: PlaybackOptions): boolean {
  if (!url) return false;
  const same = currentKey === key && isCloudPlaying();
  stopPlayback();
  if (same) return false;
  const audio = new Audio(url);
  // 本地路径会读设置里的音量；云端这条路同样要遵守，否则老人调小音量只对本地生效。
  audio.volume = clampVolume(options.volume ?? 1);
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

function clampVolume(volume: number): number {
  if (Number.isNaN(volume)) return 1;
  return Math.min(1, Math.max(0, volume));
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

export interface PlaybackOptions {
  /** 语速，0.5~2；不传读 localStorage 里的用户设置 */
  rate?: number;
  /** 音量，0~1；不传按 1 处理 */
  volume?: number;
  /** 云端音色；本地语音用不上 */
  voice?: string;
}

/**
 * 朗读一段文字（推荐入口）。
 * key 是这段内容的唯一标识：同一 key 再次调用 = 停止（toggle）。
 * 已在播放同一条内容 → 停止并返回 false；
 * 本地 SpeechSynthesis 可用 → 本地播放；
 * 否则回退到云端 TTS。
 */
export async function speakText(key: string, text: string, options: PlaybackOptions = {}): Promise<boolean> {
  if (!text?.trim()) return false;
  if (isActive() && currentKey === key) {
    stopPlayback();
    return false;
  }
  text = stripMarkdown(text);
  if (beginLocal(key, text, options)) return true;
  // 云端回退（保留 /api/tts/synthesize 作为 fallback）
  const { voice, speed: storedSpeed } = loadTtsSettings();
  const url = await synthesizeSpeech(text, { voice, speed: options.rate ?? storedSpeed });
  if (!url) return false;
  return beginCloud(key, url, options);
}
