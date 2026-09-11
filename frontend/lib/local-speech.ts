/**
 * 本地语音朗读封装（浏览器 Web Speech API）。
 *
 * 目标：优先用浏览器自带的本地中文语音朗读（SpeechSynthesis），
 * 避免每次都调用云端 TTS（qwen3-tts-flash）消耗额度。
 *
 * 组件不要直接散落地调用 window.speechSynthesis，
 * 统一经过本模块（LocalSpeechService）。
 */

/** 是否在浏览器里且支持 Web Speech 语音合成 */
function isSupported(): boolean {
  return typeof window !== 'undefined' && 'speechSynthesis' in window;
}

let voiceCache: SpeechSynthesisVoice[] | null = null;

function refreshVoiceCache(): SpeechSynthesisVoice[] {
  if (!isSupported()) return [];
  try {
    voiceCache = window.speechSynthesis.getVoices() ?? [];
  } catch {
    voiceCache = [];
  }
  return voiceCache;
}

function ensureVoicesLoaded(): void {
  if (!isSupported()) return;
  // 首次调用往往拿不到语音列表，注册 onvoiceschanged，语音就绪后再刷新
  if (voiceCache === null || voiceCache.length === 0) refreshVoiceCache();
  try {
    window.speechSynthesis.onvoiceschanged = () => { refreshVoiceCache(); };
  } catch {
    // 个别环境不允许赋值事件，忽略即可
  }
}
ensureVoicesLoaded();

function clampRate(rate: number): number {
  if (Number.isNaN(rate)) return 1;
  return Math.min(2, Math.max(0.5, rate));
}

function isChineseVoice(voice: SpeechSynthesisVoice): boolean {
  const lang = (voice.lang || '').toLowerCase().replace(/_/g, '-');
  const name = (voice.name || '').toLowerCase();
  return lang.startsWith('zh')
    || name.includes('普通话')
    || name.includes('中文')
    || name.includes('chinese');
}

/** 取当前可用的全部语音（懒加载） */
function getVoices(): SpeechSynthesisVoice[] {
  if (!isSupported()) return [];
  ensureVoicesLoaded();
  return voiceCache ?? [];
}

/**
 * 挑选最合适的中文语音。
 * 优先级：本地语音里的大陆普通话(zh-CN/zh_CN/普通话中文)
 *         → 其他大陆普通话 → 任意本地中文 → 任意中文。
 * 没有本地中文语音时，也会用其它可用的中文语音（本地播放优先于云端）。
 */
function getChineseVoice(): SpeechSynthesisVoice | null {
  const voices = getVoices();
  if (!voices.length) return null;
  const zh = voices.filter(isChineseVoice);
  if (!zh.length) return null;
  const mainland = zh.filter(v => /zh-cn/i.test(v.lang) || /普通话/i.test(v.name));
  const localMainland = mainland.filter(v => v.localService === true);
  if (localMainland.length) return localMainland[0];
  if (mainland.length) return mainland[0];
  const localZh = zh.filter(v => v.localService === true);
  if (localZh.length) return localZh[0];
  return zh[0];
}

/** 本地语音是否可用：浏览器支持，且能找到可用的中文语音 */
function isAvailable(): boolean {
  if (!isSupported()) return false;
  return getChineseVoice() !== null;
}

function pause(): void {
  if (!isSupported()) return;
  try { window.speechSynthesis.pause(); } catch { /* 忽略 */ }
}

function resume(): void {
  if (!isSupported()) return;
  try { window.speechSynthesis.resume(); } catch { /* 忽略 */ }
}

function cancel(): void {
  if (!isSupported()) return;
  try { window.speechSynthesis.cancel(); } catch { /* 忽略 */ }
}

export interface LocalSpeakOptions {
  /** 语速，0.5~2（与产品里的"语速"滑条同区间） */
  rate?: number;
  /** 指定语音；不传则自动选中文语音 */
  voice?: SpeechSynthesisVoice;
}

/**
 * 朗读一段文字。
 * 返回对应的 utterance（调用方需持有引用并监听 onend/onerror 才知道播完）。
 * 若环境不支持则返回 null。
 */
function speak(text: string, options: LocalSpeakOptions = {}): SpeechSynthesisUtterance | null {
  if (!isSupported() || !text?.trim()) return null;
  const synth = window.speechSynthesis;
  const utterance = new SpeechSynthesisUtterance(text);
  const voice = options.voice ?? getChineseVoice() ?? undefined;
  if (voice) {
    utterance.voice = voice;
    utterance.lang = voice.lang;
  }
  utterance.rate = options.rate != null ? clampRate(options.rate) : 1;
  utterance.pitch = 1;
  try {
    synth.speak(utterance);
    return utterance;
  } catch {
    return null;
  }
}

export const LocalSpeechService = {
  speak,
  pause,
  resume,
  cancel,
  isSupported,
  isAvailable,
  getVoices,
  getChineseVoice,
};
