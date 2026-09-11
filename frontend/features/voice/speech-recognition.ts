'use client';

/** 浏览器语音识别：只负责“听”，朗读仍由 lib/speech-service 负责。 */

export interface SpeechRecognitionResultEvent {
  results: ArrayLike<{ 0: { transcript: string } }>;
}

export interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
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

export function createSpeechRecognition(handlers: {
  onTranscript: (text: string) => void;
  onEnd: () => void;
  onError?: (message: string) => void;
}): SpeechRecognitionLike | null {
  const Constructor = recognizerConstructor();
  if (!Constructor) return null;
  const instance = new Constructor();
  instance.lang = 'zh-CN';
  instance.interimResults = false;
  instance.onresult = event => {
    const text = event.results?.[0]?.[0]?.transcript ?? '';
    if (text.trim()) handlers.onTranscript(text.trim());
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
