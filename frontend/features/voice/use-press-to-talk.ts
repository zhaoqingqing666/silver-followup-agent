'use client';

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { getVoiceStatus, transcribeAudio } from '@/lib/asr-tts-api';
import { createSpeechRecognition, type SpeechRecognitionLike } from './speech-recognition';

/** 按住不足这个时长视为误触，不发送 */
const MIN_HOLD_MS = 600;
/** 上滑超过这个距离 → 进入「松开取消」 */
const CANCEL_SLIDE_PX = 70;
/** 回滑到这个距离以内才恢复「松开发送」（留出迟滞，手指轻微抖动不会来回切） */
const CANCEL_DISARM_PX = 40;
/** 小于这个字节数的录音当成没录上 */
const MIN_BLOB_BYTES = 200;
/** 探测音频时长的兜底超时：MediaRecorder 产出的 WebM 常常不派发 loadedmetadata */
const DURATION_PROBE_MS = 3000;
/** 测不出时长时按这个值估：只影响语音气泡宽度，不影响能不能播 */
const FALLBACK_DURATION_SECONDS = 3;

export type VoicePhase = 'idle' | 'pressing' | 'recording' | 'transcribing';

/** 一次按住录下的原话：可直接回放，不经过识别也不经过合成 */
export interface VoiceRecording {
  audioUrl: string;
  duration: number;
}

/**
 * 一次按住说话的最终结果。
 * text 为空但 recording 非空 = 没听清，但老人的原话还在，气泡照样可以回放。
 */
export interface PressToTalkOutcome {
  text: string;
  recording: VoiceRecording | null;
}

interface RecorderResult extends VoiceRecording {
  blob: Blob;
  mimeType: string;
}

/**
 * 按住说话。主路径是 MediaRecorder 录音 + 后端 ASR 转写；
 * 后端 ASR 未开启或连不上时，退回浏览器自带的语音识别。
 *
 * 两条路径都会同时留下一份录音：识别负责出文字，录音负责留住"原话"，
 * 识别失败不会把老人的声音一起弄丢——他点一下还能听见自己刚才说了什么。
 */
export function usePressToTalk({ onResult, onNotice }: {
  onResult: (outcome: PressToTalkOutcome) => void;
  onNotice?: (message: string) => void;
}) {
  const [phase, setPhase] = useState<VoicePhase>('idle');
  const [cancelArmed, setCancelArmed] = useState(false);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [interimText, setInterimText] = useState('');

  const callbacks = useRef({ onResult, onNotice });
  // 回调放进 ref：录音器与识别实例只建一次也能拿到最新的处理函数（在 effect 里同步，避免渲染期写 ref）。
  useEffect(() => { callbacks.current = { onResult, onNotice }; });

  const mediaRecorder = useRef<MediaRecorder | null>(null);
  const audioChunks = useRef<Blob[]>([]);
  const streamRef = useRef<MediaStream | null>(null);
  const pressStart = useRef(0);
  const pressStartY = useRef(0);
  const holdingRef = useRef(false);
  const attempt = useRef(0);
  const activeRecognition = useRef<SpeechRecognitionLike | null>(null);
  /** 本次按住是否已上滑到取消：松手时据此决定发不发 */
  const cancelSendRef = useRef(false);
  /** 浏览器兜底路径拿到的定稿文字 */
  const finalTextRef = useRef('');
  /** 拿到麦克风是异步的，先记下这个 Promise，收尾时再等它 */
  const pendingRecorder = useRef<Promise<MediaRecorder | null> | null>(null);
  /** 正在收尾的录音。识别可能先结束、录音还差一点，两边都要能等到对方 */
  const pendingRecording = useRef<Promise<VoiceRecording | null> | null>(null);
  /** 本会话产生的录音 blob URL，卸载时统一交还浏览器 */
  const objectUrls = useRef<Set<string>>(new Set());
  /** 后端连不上只提示一次，别每按一次都弹 */
  const unreachableHintShown = useRef(false);
  /** 后端 ASR 是否可用；未就绪或请求失败一律按不可用处理（退回浏览器识别） */
  const asrAvailable = useRef(false);
  /** 本次按住实际走的是哪条路。按下和松手必须认同一路：
   *  状态是按下之后才就绪的话，两边各读一次 asrAvailable 会当场劈叉。 */
  const usingAsr = useRef(false);
  const statusReady = useRef<Promise<void> | null>(null);

  const notice = useCallback((message: string) => {
    callbacks.current.onNotice?.(message);
  }, []);

  const trackObjectUrl = (url: string) => { objectUrls.current.add(url); };
  const releaseObjectUrls = () => {
    objectUrls.current.forEach(url => URL.revokeObjectURL(url));
    objectUrls.current.clear();
  };

  // 启动时问一次后端的语音能力。连不上和"功能已关闭"是两回事，都要能正常降级。
  useEffect(() => {
    let cancelled = false;
    statusReady.current = getVoiceStatus().then(status => {
      if (cancelled) return;
      asrAvailable.current = status.asr;
      if (status.asrState === 'unreachable' && !unreachableHintShown.current) {
        unreachableHintShown.current = true;
        notice('暂时连不上后端语音识别，先用浏览器识别；您录下的原话仍然可以回放。');
      }
    }).catch(() => {
      if (!cancelled) asrAvailable.current = false;
    });
    return () => { cancelled = true; };
  }, [notice]);

  useEffect(() => () => releaseObjectUrls(), []);

  /** 收尾一路录音：停止 → 汇总 Blob → 生成可回放的本地 URL 和时长。
   *  返回 null 表示这段录音没有可用内容（太短 / 没录上）。 */
  const takeRecording = async (recorder: MediaRecorder): Promise<RecorderResult | null> => {
    const recorderStream = recorder.stream;
    // 先挂好回调再 stop：stop 之后才赋值，某些浏览器会在赋值前就把 onstop 派发掉
    const stopped = recorder.state === 'inactive'
      ? Promise.resolve()
      : new Promise<void>(resolve => {
        recorder.onstop = () => resolve();
        recorder.onerror = () => resolve();
      });
    try { recorder.stop(); } catch { /* 可能尚未真正开始 */ }
    recorderStream.getTracks().forEach(track => track.stop());
    await stopped;
    const mimeType = recorder.mimeType || 'audio/webm';
    const blob = new Blob(audioChunks.current, { type: mimeType });
    audioChunks.current = [];
    if (blob.size < MIN_BLOB_BYTES) return null;
    const audioUrl = URL.createObjectURL(blob);
    trackObjectUrl(audioUrl);
    const audio = new Audio(audioUrl);
    // 元数据事件不一定来（Chrome 对 MediaRecorder 产出的 WebM 常常如此），
    // 加超时兜底，免得卡在这里导致语音消息根本发不出去
    await new Promise<void>(resolve => {
      audio.onloadedmetadata = () => resolve();
      audio.onerror = () => resolve();
      setTimeout(resolve, DURATION_PROBE_MS);
    });
    const duration = Number.isFinite(audio.duration) && audio.duration > 0
      ? audio.duration : FALLBACK_DURATION_SECONDS;
    return { audioUrl, duration, blob, mimeType };
  };

  /** 与语音识别并行开一路录音：识别负责出文字，录音负责留下"原话"。
   *  两者互不依赖——识别失败不影响录音，麦克风失败也不影响识别。 */
  const startParallelRecording = async (current: number): Promise<MediaRecorder | null> => {
    if (!navigator.mediaDevices?.getUserMedia) return null;
    try {
      const mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // 拿授权期间已经松手 / 又按了一次 → 这路录音作废
      if (current !== attempt.current || !holdingRef.current) {
        mediaStream.getTracks().forEach(track => track.stop());
        return null;
      }
      const recorder = new MediaRecorder(mediaStream);
      audioChunks.current = [];
      recorder.ondataavailable = event => { if (event.data.size > 0) audioChunks.current.push(event.data); };
      recorder.start();
      mediaRecorder.current = recorder;
      streamRef.current = mediaStream;
      setStream(mediaStream);
      return recorder;
    } catch (error) {
      // 麦克风没授权只影响"回放原话"，浏览器识别照常进行
      console.warn('并行录音启动失败', error);
      return null;
    }
  };

  /** 收尾并行那一路录音：拿到可回放的"原话"。取消掉的这次直接回收，不留痕迹。 */
  const finishParallelRecording = async (cancelled: boolean): Promise<VoiceRecording | null> => {
    const pending = pendingRecorder.current;
    pendingRecorder.current = null;
    mediaRecorder.current = null;
    streamRef.current = null;
    setStream(null);
    if (!pending) return null;
    const recorder = await pending;
    if (!recorder) return null;
    const recording = await takeRecording(recorder);
    if (!recording) return null;
    if (cancelled) {
      URL.revokeObjectURL(recording.audioUrl);
      objectUrls.current.delete(recording.audioUrl);
      return null;
    }
    return { audioUrl: recording.audioUrl, duration: recording.duration };
  };

  /** 取回本次按住录下的原话：识别先结束还是录音先结束都能对上——谁先到就等谁 */
  const settleRecording = async (cancelled: boolean): Promise<VoiceRecording | null> => {
    const already = pendingRecording.current;
    if (already) { pendingRecording.current = null; return already; }
    return finishParallelRecording(cancelled);
  };

  /** 交给调用方：识别成功、只有录音、或什么都没留下 */
  const deliver = (text: string, recording: VoiceRecording | null) => {
    callbacks.current.onResult({ text, recording });
  };

  /** 丢弃进行中的一切（切页 / 卸载 / 开新对话时兜底） */
  const cancel = useCallback(() => {
    holdingRef.current = false;
    pendingRecorder.current = null;
    pendingRecording.current = null;
    activeRecognition.current?.stop();
    activeRecognition.current = null;
    const recorder = mediaRecorder.current;
    if (recorder) {
      mediaRecorder.current = null;
      streamRef.current = null;
      audioChunks.current = [];
      try {
        recorder.stream.getTracks().forEach(track => track.stop());
        recorder.stop();
      } catch { /* 可能尚未真正开始 */ }
    }
    setStream(null);
    cancelSendRef.current = false;
    setCancelArmed(false);
    setInterimText('');
    setPhase('idle');
  }, []);

  /** 按住说话：MediaRecorder 录音为主；后端 ASR 不可用时退回浏览器一次性识别 */
  const startVoice = async () => {
    if (phase === 'transcribing') return;
    callbacks.current.onNotice?.('');
    await statusReady.current;
    usingAsr.current = asrAvailable.current;

    // 兜底：浏览器语音识别（无后端 ASR）。按住期间持续听，松手才收尾发送，避免停顿就自动断开。
    if (!usingAsr.current) {
      const current = ++attempt.current;
      activeRecognition.current?.stop();
      holdingRef.current = true;
      finalTextRef.current = '';
      let settled = false;
      const finalize = (transcript: string, failure: string | null) => {
        // onerror 之后浏览器通常还会补一个 onend：只处理第一次，免得重复出气泡和提示
        if (settled || current !== attempt.current) return;
        settled = true;
        activeRecognition.current = null;
        holdingRef.current = false;
        const cancelled = cancelSendRef.current;
        cancelSendRef.current = false;
        setCancelArmed(false);
        void (async () => {
          const recording = await settleRecording(cancelled);
          setInterimText('');
          setPhase('idle');
          if (cancelled) {
            notice('已取消发送。想说的话，按住录音按钮重新发送即可。');
            return;
          }
          const text = transcript.trim();
          if (text) { deliver(text, recording); return; }
          if (recording) {
            deliver('', recording);
            notice(failure
              ? `${failure}录音已保留，可点击回放；也可以直接打字。`
              : '没有听清您说的话，录音已保留，可点击回放；也可以按住按钮重说一遍。');
            return;
          }
          notice(failure ?? '没有听清您说的话，请按住按钮重说一遍，或直接打字。');
        })();
      };
      const instance = createSpeechRecognition({
        onTranscript: text => { finalTextRef.current = text; },
        onEnd: () => finalize(finalTextRef.current, null),
        onError: message => finalize(finalTextRef.current, message),
      }, {
        interimResults: true,
        continuous: true,
        onInterim: setInterimText,
      });
      if (!instance) {
        holdingRef.current = false;
        notice('当前浏览器不支持语音识别，请使用 Chrome/Edge，或直接打字。');
        return;
      }
      activeRecognition.current = instance;
      setPhase('recording');
      try { instance.start(); }
      catch {
        activeRecognition.current = null;
        holdingRef.current = false;
        setInterimText('');
        setPhase('idle');
        notice('语音识别启动失败，请直接打字。');
        return;
      }
      // 关键：录音和识别同时跑。识别负责转文字，录音负责留下"原话"——
      // 这样即使后端 ASR 用不了、退到浏览器识别，老人的话也照样能回放。
      pendingRecorder.current = startParallelRecording(current);
      return;
    }

    if (!navigator.mediaDevices?.getUserMedia) {
      notice('当前环境无法使用麦克风（需 localhost 或 HTTPS 访问），请直接打字。');
      return;
    }
    setPhase('pressing');
    const current = ++attempt.current;
    holdingRef.current = true;
    pressStart.current = Date.now();
    try {
      const mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // 拿授权期间已经松手 / 又按了一次 → 丢弃这路输入
      if (current !== attempt.current || !holdingRef.current) {
        mediaStream.getTracks().forEach(track => track.stop());
        return;
      }
      streamRef.current = mediaStream;
      setStream(mediaStream);
      const recorder = new MediaRecorder(mediaStream);
      mediaRecorder.current = recorder;
      audioChunks.current = [];
      recorder.ondataavailable = event => { if (event.data.size > 0) audioChunks.current.push(event.data); };
      recorder.start();
      setPhase('recording');
    } catch (error) {
      console.warn('录音失败', error);
      holdingRef.current = false;
      setPhase('idle');
      notice('麦克风授权被拒绝：请点击地址栏麦克风图标允许，或直接打字发送。');
    }
  };

  /** 丢掉一路录音：轨迹停下来、麦克风交还浏览器、缓冲区清空 */
  const discardRecorder = (recorder: MediaRecorder) => {
    mediaRecorder.current = null;
    streamRef.current = null;
    setStream(null);
    recorder.stream.getTracks().forEach(track => track.stop());
    try { recorder.stop(); } catch { /* 可能尚未真正开始 */ }
    audioChunks.current = [];
  };

  /** 松开：已上滑取消则丢弃；短按当误触；否则收尾录音 → 后端 ASR 转写 → 交给调用方 */
  const stopVoice = async () => {
    if (!usingAsr.current) {
      // 取消标记必须在松手这一刻同步读出来（handleRelease 已把 holdingRef 置 false），
      // 再交给 onend 收尾：识别和录音谁先结束都能等到对方。
      const cancelled = cancelSendRef.current;
      if (!cancelled) setPhase('transcribing');
      pendingRecording.current = finishParallelRecording(cancelled);
      activeRecognition.current?.stop();
      return;
    }
    const recorder = mediaRecorder.current;
    if (!recorder) return;
    // 上滑取消（或指针被系统接管中断）：丢弃本次录音，不识别不发送
    if (cancelSendRef.current) {
      cancelSendRef.current = false;
      setCancelArmed(false);
      discardRecorder(recorder);
      setPhase('idle');
      notice('已取消发送。想说的话，按住录音按钮重新发送即可。');
      return;
    }
    // 短按：不足阈值视为误触，丢弃录音不发送
    if (Date.now() - pressStart.current < MIN_HOLD_MS) {
      discardRecorder(recorder);
      setPhase('idle');
      notice('说话时间太短，请按住按钮说话');
      return;
    }
    mediaRecorder.current = null;
    streamRef.current = null;
    setStream(null);
    setPhase('transcribing');
    // 先拿到可回放的录音（本地 URL + 真实时长），再做识别：
    // 后面无论识别成功还是失败，这段原话都已经在结果里，不会被失败请求带走。
    const recording = await takeRecording(recorder);
    if (!recording) { setPhase('idle'); return; }
    const text = await transcribeAudio(recording.blob, recording.mimeType);
    const trimmed = (text || '').trim();
    const replayable = { audioUrl: recording.audioUrl, duration: recording.duration };
    setPhase('idle');
    if (!trimmed) {
      deliver('', replayable);
      notice(text === null
        ? '语音识别服务暂时不可用，录音已保留，可点击回放；也可以直接打字。'
        : '没有听清您说的话，录音已保留，可点击回放；请按住按钮重说一遍，或直接打字。');
      return;
    }
    deliver(trimmed, replayable);
  };

  /** 按住语音按钮：捕获指针（手指滑到按钮外也能收到移动/抬起），开始录音 */
  const onPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (phase === 'transcribing') return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    pressStartY.current = event.clientY;
    cancelSendRef.current = false;
    setCancelArmed(false);
    void startVoice();
  };

  /** 按住过程中跟踪手指：上滑超过阈值进入"松开取消"，滑回则恢复"松开发送" */
  const onPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (!holdingRef.current) return;
    const distance = pressStartY.current - event.clientY;
    // 已经武装取消时用更小的恢复距离：迟滞能让手指抖动不至于来回切换
    const armed = cancelSendRef.current ? distance >= CANCEL_DISARM_PX : distance >= CANCEL_SLIDE_PX;
    if (armed !== cancelSendRef.current) { cancelSendRef.current = armed; setCancelArmed(armed); }
  };

  /** 松手：未取消 → 停录识别并交给调用方；已上滑取消 → 丢弃不发送 */
  const onPointerUp = () => {
    holdingRef.current = false;
    void stopVoice();
  };

  /** 指针被系统接管 / 意外中断：一律当作取消，不发送 */
  const onPointerCancel = () => {
    cancelSendRef.current = true;
    setCancelArmed(false);
    holdingRef.current = false;
    void stopVoice();
  };

  return {
    phase,
    stream,
    cancelArmed,
    interimText,
    /** 正在按住（含还没拿到麦克风的 pressing）：按钮据此显示"松开发送" */
    holding: phase === 'pressing' || phase === 'recording',
    onPointerDown,
    onPointerMove,
    onPointerUp,
    onPointerCancel,
    cancel,
  };
}
