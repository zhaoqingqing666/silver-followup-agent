'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { stopSpeech } from '@/lib/speech-service';
import { createSpeechRecognition, type SpeechRecognitionLike } from './speech-recognition';

/** 按住说话：按下开始识别，松手结束并把识别结果交给回调。 */
export function useVoiceInput({ onTranscript, onNotice }: {
  onTranscript: (text: string) => void;
  onNotice?: (message: string) => void;
}) {
  const [listening, setListening] = useState(false);
  const recognition = useRef<SpeechRecognitionLike | null>(null);
  // 回调放进 ref，识别实例只创建一次也能拿到最新的处理函数（在 effect 里同步，避免渲染期写 ref）。
  const callbacks = useRef({ onTranscript, onNotice });
  useEffect(() => { callbacks.current = { onTranscript, onNotice }; });

  const stop = useCallback(() => {
    recognition.current?.stop();
    recognition.current = null;
    setListening(false);
  }, []);

  const start = useCallback(() => {
    if (recognition.current) return;
    // 用户主动说话时停掉上一条播报，避免麦克风把助手自己的声音收进去。
    stopSpeech();
    const instance = createSpeechRecognition({
      onTranscript: text => callbacks.current.onTranscript(text),
      onEnd: () => {
        recognition.current = null;
        setListening(false);
      },
      onError: message => callbacks.current.onNotice?.(message),
    });
    if (!instance) {
      callbacks.current.onNotice?.('当前浏览器不支持语音识别，请直接点下面的按钮操作。');
      return;
    }
    recognition.current = instance;
    setListening(true);
    instance.start();
  }, []);

  useEffect(() => () => {
    recognition.current?.stop();
    recognition.current = null;
  }, []);

  return { listening, start, stop };
}
