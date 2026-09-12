'use client';

/** 浏览器语音识别：只负责“听”，朗读仍由 lib/tts-player 负责。 */

export interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

export interface SpeechRecognitionResultLike {
  0: SpeechRecognitionAlternativeLike;
  /** 这一段是否已定稿。按住说话时要靠它把中途字幕和最终文字分开。 */
  isFinal?: boolean;
}

export interface SpeechRecognitionResultEvent {
  results: ArrayLike<SpeechRecognitionResultLike>;
  /** 本次事件从第几段开始是新的；不认这个字段会把前面几段重复累加。 */
  resultIndex?: number;
}

export interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  continuous?: boolean;
  onresult: ((event: SpeechRecognitionResultEvent) => void) | null;
  onend: (() => void) | null;
  onerror: ((event: { error?: string }) => void) | null;
  start(): void;
  stop(): void;
}

interface RecognizerScope {
  SpeechRecognition?: new () => SpeechRecognitionLike;
  webkitSpeechRecognition?: new () => SpeechRecognitionLike;
}

// 以前只认 webkit 前缀，标准写法在部分浏览器上会被误判成“不支持语音”。
function recognizerConstructor(): (new () => SpeechRecognitionLike) | null {
  if (typeof window === 'undefined') return null;
  const scope = window as unknown as RecognizerScope;
  return scope.SpeechRecognition ?? scope.webkitSpeechRecognition ?? null;
}

export interface SpeechRecognitionOptions {
  /**
   * 打开中途结果。按住说话时要一边听一边显示字幕，所以需要 true；
   * 点一下就识别一次的场景保持默认 false。
   */
  interimResults?: boolean;
  /** 中途（还没定稿）的累计文字，用来显示实时字幕 */
  onInterim?: (text: string) => void;
  /** 按住期间不许浏览器自己断开：默认 false（说完一句就结束） */
  continuous?: boolean;
}

export function createSpeechRecognition(handlers: {
  /** 已定稿的累计文字。按连续模式说话时会多次回调，取最后一次即可。 */
  onTranscript: (text: string) => void;
  onEnd: () => void;
  onError?: (message: string) => void;
}, options: SpeechRecognitionOptions = {}): SpeechRecognitionLike | null {
  const Constructor = recognizerConstructor();
  if (!Constructor) return null;
  const instance = new Constructor();
  instance.lang = 'zh-CN';
  instance.interimResults = options.interimResults === true;
  if (options.continuous != null) instance.continuous = options.continuous;
  // 定稿的文字要自己累计：连续模式下每来一段新的就追加，不能只看 results[0]。
  let finalText = '';
  instance.onresult = event => {
    const results = event.results;
    const total = results?.length ?? 0;
    let interim = '';
    for (let index = event.resultIndex ?? 0; index < total; index += 1) {
      const chunk = results[index];
      const text = chunk?.[0]?.transcript ?? '';
      if (chunk?.isFinal) finalText += text;
      else interim += text;
    }
    if (interim) options.onInterim?.(`${finalText}${interim}`.trim());
    const settled = finalText.trim();
    if (settled) handlers.onTranscript(settled);
  };
  instance.onend = () => handlers.onEnd();
  // 以前没有 onerror，识别失败时“正在听…”会一直挂着。
  instance.onerror = event => handlers.onError?.(recognitionErrorMessage(event?.error));
  return instance;
}

export function recognitionErrorMessage(code?: string) {
  if (code === 'not-allowed' || code === 'service-not-allowed') {
    return '麦克风还没有获得授权，请在浏览器里允许使用麦克风后再试。';
  }
  if (code === 'no-speech' || code === 'audio-capture') {
    return '没有听到声音，请靠近一点，按住按钮再说一次。';
  }
  return '这次没有听清，请再说一次，或者直接点下面的按钮操作。';
}
