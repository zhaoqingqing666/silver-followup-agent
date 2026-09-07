'use client';

export interface SpeechState {
  messageId: string | null;
  speaking: boolean;
}

type Listener = (state: SpeechState) => void;

let current: SpeechState = { messageId: null, speaking: false };
const listeners = new Set<Listener>();

function emit(state: SpeechState) {
  current = state;
  listeners.forEach(listener => listener(state));
}

export function subscribeSpeech(listener: Listener) {
  listeners.add(listener);
  listener(current);
  return () => { listeners.delete(listener); };
}

export function stopSpeech() {
  if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
    window.speechSynthesis.cancel();
  }
  emit({ messageId: null, speaking: false });
}

export function speakText(
  text: string,
  messageId: string,
  options: { rate?: number; volume?: number } = {},
) {
  if (typeof window === 'undefined' || !('speechSynthesis' in window) || !text.trim()) return false;
  stopSpeech();
  const utterance = new SpeechSynthesisUtterance(text.trim());
  utterance.lang = 'zh-CN';
  utterance.rate = options.rate ?? 0.9;
  utterance.volume = options.volume ?? 1;
  utterance.onstart = () => emit({ messageId, speaking: true });
  utterance.onend = () => emit({ messageId: null, speaking: false });
  utterance.onerror = () => emit({ messageId: null, speaking: false });
  window.speechSynthesis.speak(utterance);
  return true;
}
