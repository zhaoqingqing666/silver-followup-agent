'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { Camera, Check, CalendarSearch, ChevronDown, ClipboardCheck, History, Image, LoaderCircle, MessageSquareText, Mic, Plus, RefreshCw, Send, Sparkles, X } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { confirmAgentActions, getConversationHistory, listConversations, sendAgentAction, sendAgentImageMessage, sendAgentMessage, startConversation } from '@/lib/agent-api';
import { getVoices, getVoiceStatus, transcribeAudio, type VoiceStatus } from '@/lib/asr-tts-api';
import { compressImageFile } from '@/lib/image-compress';
import { speakText, stopPlayback } from '@/lib/tts-player';
import type { AgentTurnResponse, ChatMessage, ConversationStatus, ConversationSummary, TabId, ToolTrace } from '@/types/domain';
import { ConfirmationCardView, PlanCard, ResultCardView } from './assistant-cards';
import { ChatBubble } from './chat-bubble';
import { ToolTracePanel } from './tool-trace-panel';
import { CameraCapture } from './camera-capture';

const CONVERSATION_KEY = 'silver-agent-current-conversation';
const TTS_VOICE_KEY = 'silver-agent-tts-voice';
const TTS_SPEED_KEY = 'silver-agent-tts-speed';
/** 长按阈值：按住不足此时长视为短按，丢弃录音不发送 */
const MIN_HOLD_MS = 600;

/** 上滑取消阈值：按住时手指上滑超过该距离进入"松开取消"；滑回不足该距离恢复"松开发送" */
const CANCEL_SLIDE_PX = 70;
const CANCEL_DISARM_PX = 40;

/** 一次最多可上传的图片张数 */
const MAX_IMAGES = 3;

/** 语音交互的阶段：待机 → 按住 → 录音 → 识别中 → 发送中 */
type VoicePhase = 'idle' | 'pressing' | 'recording' | 'transcribing' | 'sending';

/**
 * 图片识别的阶段文案。后端是同步返回的，拿不到真实进度，但老人干等一个"处理中"很容易以为卡住了，
 * 所以按大致耗时轮换文案，让它看得出"在往前走"。最后一档会一直停住，直到真的返回。
 */
const IMAGE_STAGES = ['正在识别图片…', '正在核对信息…', '正在整理结果…'] as const;
/** 每档停留时长（毫秒），按识别实际耗时估的 */
const IMAGE_STAGE_MS = 2500;

/** 简易音量动画：rAF 驱动，直接改 DOM 高度，避免 60fps 的 React 重渲染 */
function LevelMeter({ stream, active }: { stream: MediaStream | null; active: boolean }) {
  const barRefs = useRef<(HTMLDivElement | null)[]>([]);
  useEffect(() => {
    if (!active || !stream) return;
    const AudioCtor = window.AudioContext || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtor) return;
    const context = new AudioCtor();
    const source = context.createMediaStreamSource(stream);
    const analyser = context.createAnalyser();
    analyser.fftSize = 512;
    source.connect(analyser);
    const buffer = new Uint8Array(analyser.fftSize);
    let frame = 0;
    const tick = () => {
      analyser.getByteTimeDomainData(buffer);
      let sum = 0;
      for (let i = 0; i < buffer.length; i += 1) {
        const value = (buffer[i] - 128) / 128;
        sum += value * value;
      }
      const rms = Math.sqrt(sum / buffer.length);
      const peak = Math.max(0.08, Math.min(1, rms * 6));
      const now = Date.now();
      barRefs.current.forEach((bar, i) => {
        if (!bar) return;
        const phase = (i / barRefs.current.length) * Math.PI;
        const height = Math.max(8, peak * 36 * (0.55 + 0.45 * Math.sin(now / 140 + phase)));
        bar.style.height = `${Math.min(36, Math.round(height))}px`;
      });
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => { cancelAnimationFrame(frame); void context.close(); };
  }, [stream, active]);
  return (
    <div className="flex h-9 items-end justify-center gap-1.5" aria-hidden="true">
      {[0, 1, 2, 3, 4].map(i => (
        <div key={i} ref={el => { barRefs.current[i] = el; }} className="w-2 rounded-full bg-red-500" style={{ height: 8 }} />
      ))}
    </div>
  );
}

/** 相对时间显示（历史对话列表用） */
function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes}分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}小时前`;
  return `${Math.floor(hours / 24)}天前`;
}

const stageLabels: Record<string, string> = {
  ASK_HOSPITAL: '确认医院', ASK_DEPARTMENT: '确认科室', ASK_DATE: '确认日期',
  ASK_ALTERNATIVE: '补充偏好', ASK_COMPANION: '陪同安排', ASK_TRAVEL: '出行安排',
  ASK_NOTIFY: '家属通知', ASK_TRANSPORT: '交通方式', READY_TO_PLAN: '检查计划', SELECT_PERIOD: '选择上午或下午', CONFIRM_SLOT: '确认推荐时间', SELECT_SLOT: '选择具体时间', NO_SLOT: '更换时间', CONFLICT: '处理冲突',
  AWAITING_CONFIRMATION: '等待确认', COMPLETED: '办理完成', CANCELLED: '已取消',
};

export function AssistantView({ onNavigate, autoSpeakEnabled }: { onNavigate: (tab: TabId) => void; autoSpeakEnabled: boolean }) {
  const [conversationId, setConversationId] = useState('');
  // 会话生命周期：ACTIVE 可以继续聊；CLOSED 已结束，只读。后端是唯一判定方，
  // 前端只是如实呈现——已结束的对话不再接受新消息，要咨询就开一段新对话。
  const [conversationStatus, setConversationStatus] = useState<ConversationStatus>('ACTIVE');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [turn, setTurn] = useState<AgentTurnResponse | null>(null);
  // 本轮真实产生的执行轨迹。后端返回的是整段对话的累计轨迹，这里只取本轮新增的部分，
  // 避免把历史调用重复堆到老人眼前。
  const [turnTraces, setTurnTraces] = useState<ToolTrace[]>([]);
  const traceCursor = useRef(0);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(true);
  const [voicePhase, setVoicePhase] = useState<VoicePhase>('idle');
  /** 这一轮 busy 是不是图片识别：是的话忙碌提示按 IMAGE_STAGES 分阶段显示 */
  const [imageTurn, setImageTurn] = useState(false);
  const [imageStage, setImageStage] = useState(0);
  const [cancelArmed, setCancelArmed] = useState(false);
  // 三个服务各自独立：连不上后端和"功能已关闭"是两回事，不能一个接口失败就把三个都判成 false
  const [voiceEnabled, setVoiceEnabled] = useState<VoiceStatus>({ asr: false, tts: false, vl: false, asrState: 'unknown', ttsState: 'unknown', vlState: 'unknown' });
  const [pendingImages, setPendingImages] = useState<string[]>([]);
  const [voices, setVoices] = useState<Array<{ id: string; label: string }>>([]);
  const [ttsVoice, setTtsVoice] = useState<string>(() => window.localStorage.getItem(TTS_VOICE_KEY) || 'Cherry');
  const [ttsSpeed, setTtsSpeed] = useState<number>(() => parseFloat(window.localStorage.getItem(TTS_SPEED_KEY) || '1.0'));
  const [showTtsSettings, setShowTtsSettings] = useState(false);
  const [hint, setHint] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyList, setHistoryList] = useState<ConversationSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [interimText, setInterimText] = useState('');

  const mediaRecorder = useRef<MediaRecorder | null>(null);
  const audioChunks = useRef<Blob[]>([]);
  const pressStart = useRef<number>(0);
  const pressStartY = useRef(0);
  const streamRef = useRef<MediaStream | null>(null);
  const holdingRef = useRef(false);
  const voiceAttempt = useRef(0);
  const activeRecognition = useRef<{ stop: () => void } | null>(null);
  /** 本次按住是否已进入"上滑取消"：松手时据此决定发不发 */
  const cancelSendRef = useRef(false);
  const micButtonRef = useRef<HTMLButtonElement | null>(null);
  const bottomAnchor = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  /** 浏览器兜底识别时并行开的录音器：拿到麦克风是异步的，先记下这个 Promise */
  const pendingRecorder = useRef<Promise<MediaRecorder | null> | null>(null);
  /** 正在收尾的录音。用 Promise 存是因为识别可能先结束、
   *  录音还差最后一点才拿到 Blob——两边都要等对方，不能谁先到谁说话 */
  const pendingRecording = useRef<Promise<{ audioUrl: string; duration: number } | null> | null>(null);
  /** 本会话产生的所有录音 blob URL：消息离开界面时统一交还浏览器，避免内存泄漏 */
  const objectUrls = useRef<Set<string>>(new Set());
  const unreachableHintShown = useRef(false);

  /** 记住一个录音 blob URL，等消息真正离开界面时再释放（不能发完就 revoke） */
  const trackObjectUrl = (url: string) => { objectUrls.current.add(url); };
  const releaseObjectUrls = () => {
    objectUrls.current.forEach(url => URL.revokeObjectURL(url));
    objectUrls.current.clear();
  };

  /** 收尾一路录音：停止 → 汇总 Blob → 生成可回放的本地 URL 和时长。
   *  返回 null 表示这段录音没有可用内容（太短 / 没录上）。 */
  const takeRecording = async (recorder: MediaRecorder): Promise<{ audioUrl: string; duration: number; blob: Blob; mimeType: string } | null> => {
    const stream = recorder.stream;
    // 先挂好回调再 stop：stop 之后才赋值，某些浏览器会在赋值前就把 onstop 派发掉
    const stopped = recorder.state === 'inactive'
      ? Promise.resolve()
      : new Promise<void>(resolve => {
        recorder.onstop = () => resolve();
        recorder.onerror = () => resolve();
      });
    try { recorder.stop(); } catch { /* 可能尚未真正开始 */ }
    stream.getTracks().forEach(track => track.stop());
    await stopped;
    const mimeType = recorder.mimeType || 'audio/webm';
    const blob = new Blob(audioChunks.current, { type: mimeType });
    audioChunks.current = [];
    if (blob.size < 200) return null;
    const audioUrl = URL.createObjectURL(blob);
    trackObjectUrl(audioUrl);
    const audio = new Audio(audioUrl);
    // 元数据事件不一定来（Chrome 对 MediaRecorder 产出的 WebM 常常如此），
    // 加超时兜底，免得卡在这里导致语音消息根本发不出去
    await new Promise<void>(resolve => {
      audio.onloadedmetadata = () => resolve();
      audio.onerror = () => resolve();
      setTimeout(resolve, 3000);
    });
    // 测不出有效秒数就按 3 秒估：只影响气泡宽度，不影响能不能播放
    const duration = Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : 3;
    return { audioUrl, duration, blob, mimeType };
  };

  /** 与语音识别并行开一路录音：识别负责出文字，录音负责留下"原话"。
   *  两者互不依赖——识别失败不影响录音，麦克风失败也不影响识别。 */
  const startParallelRecording = async (attempt: number): Promise<MediaRecorder | null> => {
    if (!navigator.mediaDevices?.getUserMedia) return null;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // 拿授权期间已经松手 / 又按了一次 → 这路录音作废
      if (attempt !== voiceAttempt.current || !holdingRef.current) {
        stream.getTracks().forEach(track => track.stop());
        return null;
      }
      const recorder = new MediaRecorder(stream);
      audioChunks.current = [];
      recorder.ondataavailable = event => { if (event.data.size > 0) audioChunks.current.push(event.data); };
      recorder.start();
      mediaRecorder.current = recorder;
      streamRef.current = stream;
      return recorder;
    } catch (error) {
      // 麦克风没授权只影响"回放原话"，浏览器识别照常进行
      console.warn('并行录音启动失败', error);
      return null;
    }
  };

  /** 收尾并行那一路录音：拿到可回放的"原话"。取消掉的这次直接回收，不留痕迹。 */
  const finishParallelRecording = async (cancelled: boolean): Promise<{ audioUrl: string; duration: number } | null> => {
    const pending = pendingRecorder.current;
    pendingRecorder.current = null;
    mediaRecorder.current = null;
    streamRef.current = null;
    if (!pending) return null;
    const recorder = await pending;
    if (!recorder) return null;
    const recording = await takeRecording(recorder);
    if (!recording) return null;
    if (cancelled) {
      // 上滑取消：这段录音不会入列，URL 当场回收，别留着占内存
      URL.revokeObjectURL(recording.audioUrl);
      objectUrls.current.delete(recording.audioUrl);
      return null;
    }
    return { audioUrl: recording.audioUrl, duration: recording.duration };
  };

  /** 取回本次按住录下的原话：识别先结束还是录音先结束都能对上——谁先到就等谁 */
  const settleRecording = async (cancelled: boolean): Promise<{ audioUrl: string; duration: number } | null> => {
    const already = pendingRecording.current;
    if (already) { pendingRecording.current = null; return already; }
    return finishParallelRecording(cancelled);
  };

  // 启动时检查语音能力 + 加载音色列表
  useEffect(() => {
    void getVoiceStatus().then(status => {
      setVoiceEnabled(status);
      // 后端连不上 ≠ 语音功能关闭：说清楚现在走的是浏览器识别，且录音仍然能回放。只提示一次。
      if (status.asrState === 'unreachable' && !unreachableHintShown.current) {
        unreachableHintShown.current = true;
        showHint('暂时连不上后端语音识别，先用浏览器识别；您录下的原话仍然可以回放。');
      }
    });
    void getVoices().then(list => { if (list.length) setVoices(list); });
  }, []);

  // 组件卸载：把这段会话里所有录音 blob URL 交还浏览器
  useEffect(() => () => releaseObjectUrls(), []);

  // 保存用户 TTS 设置到 localStorage
  useEffect(() => { window.localStorage.setItem(TTS_VOICE_KEY, ttsVoice); }, [ttsVoice]);
  useEffect(() => { window.localStorage.setItem(TTS_SPEED_KEY, String(ttsSpeed)); }, [ttsSpeed]);

  /** 底部浮动提示条（3 秒自动消失，不污染对话记录） */
  const showHint = (text: string) => {
    setHint(text);
    window.setTimeout(() => setHint(current => (current === text ? '' : current)), 3200);
  };

  /** 已结束的对话只能查看：拦下所有发送动作。老人要继续问，就点「新对话」开一段新的。 */
  const blockedByClosed = () => {
    if (conversationStatus !== 'CLOSED') return false;
    showHint('这段对话已经结束了，点一下「新对话」就能接着咨询。');
    return true;
  };

  const addAssistant = (response: AgentTurnResponse) => {
    setTurn(response);
    const all = response.toolTraces ?? [];
    setTurnTraces(all.slice(traceCursor.current)); // 只展示本轮真实新增的执行记录
    traceCursor.current = all.length;
    // 后端在轮次/内容长度到上限时会自动收尾旧会话并开一段新会话，返回的是新会话 id。
    // 这里跟着切过去，并给老人一句人话提示（回复正文里已有说明，这里只是不让他找不着北）。
    const switched = !!conversationId && response.conversationId !== conversationId;
    setConversationStatus('ACTIVE');
    if (switched) showHint('刚才那段对话已经结束，我为您开启了一段新对话，接着说就行。');
    setConversationId(response.conversationId);
    window.localStorage.setItem(CONVERSATION_KEY, response.conversationId);
    const id = crypto.randomUUID();
    setMessages(items => [...items, { id, role: 'assistant', text: response.reply }]);
    // 自动朗读偏好：开启时自动朗读每条新回复（本组件仅在"复诊助手"页挂载，天然只在对话页朗读）
    if (autoSpeakEnabled && response.reply?.trim()) void speakText(id, response.reply);
    // 后端指示自动跳转（如"看看我的事项" → 打开事项页）
    if (response.autoAction === 'OPEN_TASKS') onNavigate('tasks');
  };

  const begin = async () => {
    setBusy(true);
    releaseObjectUrls(); // 旧消息连同它们的录音一起离场
    setMessages([]);
    setTurn(null);
    setTurnTraces([]);
    setConversationStatus('ACTIVE');
    traceCursor.current = 0;
    try { addAssistant(await startConversation()); }
    catch { setMessages([{ id: 'offline', role: 'assistant', text: '后端服务还没有启动。请先运行 Java 后端，再点"重新连接"。' }]); }
    finally { setBusy(false); }
  };

  /** 开启新对话：清空当前界面和本地记录，重新开始。
   *  后端会把旧的 ACTIVE 会话置为 CLOSED（保留在历史里，只能查看），再开一段新的。 */
  const newConversation = () => {
    stopPlayback();
    cancelActiveVoice();
    setHistoryOpen(false);
    window.localStorage.removeItem(CONVERSATION_KEY);
    setConversationId('');
    setConversationStatus('ACTIVE');
    setTurn(null);
    releaseObjectUrls();
    setMessages([]);
    void begin();
  };

  /** 打开历史对话面板并拉取最近 3 天的列表 */
  const loadHistory = async () => {
    setHistoryOpen(true);
    setHistoryLoading(true);
    try { setHistoryList(await listConversations()); }
    catch { setHistoryList([]); }
    finally { setHistoryLoading(false); }
  };

  /** 切换到某条历史对话 */
  const openConversation = async (id: string) => {
    if (id === conversationId) { setHistoryOpen(false); return; }
    stopPlayback();
    cancelActiveVoice();
    setHistoryOpen(false);
    setBusy(true);
    try {
      const history = await getConversationHistory(id);
      setConversationId(history.conversationId);
      setConversationStatus(history.status ?? 'ACTIVE');
      setTurn(history.current);
      // 历史对话不重放执行过程，避免旧轨迹堆到老人眼前
      setTurnTraces([]); traceCursor.current = (history.current?.toolTraces ?? []).length;
      // 历史消息只映射 { id, role, text }：录音不落库，历史里也没有 audioUrl，
      // 于是自然回退成"🎤 + 文字"，不会去点一个早就失效的 blob 链接
      releaseObjectUrls();
      setMessages(history.messages.map(message => ({
        id: String(message.id), role: message.role, text: message.content,
      })));
      window.localStorage.setItem(CONVERSATION_KEY, history.conversationId);
    }
    catch { showHint('这条对话加载失败，请稍后再试。'); }
    finally { setBusy(false); }
  };

  useEffect(() => {
    const restore = async () => {
      const savedId = window.localStorage.getItem(CONVERSATION_KEY);
      if (!savedId) { await begin(); return; }
      setBusy(true);
      try {
        const history = await getConversationHistory(savedId);
        setConversationId(history.conversationId);
        setConversationStatus(history.status ?? 'ACTIVE');
        setTurn(history.current);
        // 历史对话不重放执行过程，避免旧轨迹堆到老人眼前
        setTurnTraces([]); traceCursor.current = (history.current?.toolTraces ?? []).length;
        // 同上：历史只保留文字，没有录音可回放
        releaseObjectUrls();
        setMessages(history.messages.map(message => ({
          id: String(message.id), role: message.role, text: message.content,
        })));
      } catch {
        window.localStorage.removeItem(CONVERSATION_KEY);
        await begin();
        return;
      }
      setBusy(false);
    };
    void restore();
  }, []);

  useEffect(() => {
    bottomAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, turn?.stage]);

  // 图片识别期间按大致耗时轮换阶段文案：真实进度拿不到，但一直停在一句"处理中"会让老人以为卡死了。
  // 走到最后一档就停住，直到后端真的返回。
  useEffect(() => {
    if (!busy || !imageTurn) return;
    setImageStage(0);
    const timer = window.setInterval(
      () => setImageStage(stage => Math.min(stage + 1, IMAGE_STAGES.length - 1)),
      IMAGE_STAGE_MS,
    );
    return () => window.clearInterval(timer);
  }, [busy, imageTurn]);

  const sendText = async (raw: string, isVoice = false) => {
    const value = raw.trim();
    if (!value || !conversationId || busy || blockedByClosed()) return;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: value, isVoice }]);
    setInput('');
    setBusy(true);
    try { addAssistant(await sendAgentMessage(conversationId, value, isVoice)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '服务暂时不可用，请稍后重试。' }]); }
    finally { setBusy(false); }
  };

  const sendAction = async (action: string, value: string, label: string) => {
    if (!conversationId || busy || blockedByClosed()) return;
    if (action === 'OPEN_TASKS') { onNavigate('tasks'); return; }
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: label }]);
    setBusy(true);
    try { addAssistant(await sendAgentAction(conversationId, action, value)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '操作没有成功，请稍后重试。' }]); }
    finally { setBusy(false); }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pendingImages.length) { void sendPendingImage(); }
    else { void sendText(input); }
  };

  const confirm = async (approved: boolean) => {
    if (!conversationId || busy || blockedByClosed()) return;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: approved ? '我确认执行这些操作' : '暂不执行' }]);
    setBusy(true);
    try {
      const response = await confirmAgentActions(conversationId, approved);
      addAssistant(response);
      if (response.result) window.dispatchEvent(new Event('silver-agent-appointments-updated'));
    }
    catch { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: '确认操作没有成功，请重试。系统不会重复预约。' }]); }
    finally { setBusy(false); }
  };

  /** 取消并清空进行中的语音输入（开新对话 / 切换历史时兜底清理） */
  const cancelActiveVoice = () => {
    holdingRef.current = false;
    // 还没收尾的录音（含 Promise 形态）一并作废：这次连"取消"都算不上，直接丢
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
    cancelSendRef.current = false;
    setCancelArmed(false);
    setVoicePhase('idle');
  };

  /** 识别完成 → 松手直发：当作"语音消息"交给 Agent。关键操作由后端复述确认、
   *  预约提交/取消由后端确认卡兜底，前端不再人工确认。
   *  如果有待发送的图片，语音就作为图片的辅助说明，和图片一起发出去。 */
  const submitRecognizedText = async (text: string) => {
    if (!conversationId) { setVoicePhase('idle'); showHint('尚未连接对话，请先点"重新连接"，再说一次。'); return; }
    if (blockedByClosed()) { setVoicePhase('idle'); return; }
    setVoicePhase('sending');
    setBusy(true);
    try {
      if (pendingImages.length > 0) {
        // 图片 + 语音：语音转写文字作为图片说明，一起发（气泡已在录音阶段带上了图片）
        const dataUrls = pendingImages;
        setPendingImages([]);
        addAssistant(await sendAgentImageMessage(conversationId, dataUrls, text));
      } else {
        addAssistant(await sendAgentMessage(conversationId, text, true));
      }
    } catch (error) {
      setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '服务暂时不可用，请稍后重试。' }]);
    } finally {
      setBusy(false);
      setVoicePhase('idle');
    }
  };

  /** 按住语音按钮：捕获指针（手指滑到按钮外也能收到移动/抬起），开始录音 */
  const startVoicePress = (event: React.PointerEvent<HTMLButtonElement>) => {
    if (voicePhase === 'transcribing' || voicePhase === 'sending') return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    pressStartY.current = event.clientY;
    cancelSendRef.current = false;
    setCancelArmed(false);
    void startVoice();
  };

  /** 按住过程中跟踪手指：上滑超过阈值进入"松开取消"，滑回则恢复"松开发送" */
  const handleVoiceMove = (event: React.PointerEvent<HTMLButtonElement>) => {
    if (!holdingRef.current) return;
    const distance = pressStartY.current - event.clientY;
    const armed = cancelSendRef.current ? distance >= CANCEL_DISARM_PX : distance >= CANCEL_SLIDE_PX;
    if (armed !== cancelSendRef.current) { cancelSendRef.current = armed; setCancelArmed(armed); }
  };

  /** 松手：未取消 → 停录识别并直发；已上滑取消 → 丢弃不发送 */
  const handleVoiceRelease = () => {
    holdingRef.current = false;
    void stopVoice();
  };

  /** 指针被系统接管 / 意外中断：一律当作取消，不发送 */
  const handleVoiceCancel = () => {
    cancelSendRef.current = true;
    setCancelArmed(false);
    holdingRef.current = false;
    void stopVoice();
  };

  /** 按下说话：MediaRecorder 录音为主；后端 ASR 未开启时退回浏览器一次性识别 */
  const startVoice = async () => {
    if (voicePhase === 'transcribing' || voicePhase === 'sending') return;
    setHint('');

    // 兜底：浏览器语音识别（无后端 ASR）。按住期间持续听，松手才收尾发送，避免停顿就自动断开。
    if (!voiceEnabled.asr) {
      const Constructor = (window as unknown as { webkitSpeechRecognition?: new () => any }).webkitSpeechRecognition;
      if (!Constructor) { showHint('当前浏览器不支持语音识别，请使用 Chrome/Edge，或直接打字。'); return; }
      const attempt = ++voiceAttempt.current;
      activeRecognition.current?.stop();
      const instance = new Constructor();
      activeRecognition.current = instance;
      instance.lang = 'zh-CN';
      instance.continuous = true;
      instance.interimResults = true;
      holdingRef.current = true;
      let finalText = '';
      instance.onresult = (event: any) => {
        if (attempt !== voiceAttempt.current) return;
        let interim = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const chunk = event.results[i];
          if (chunk.isFinal) finalText += chunk[0].transcript;
          else interim += chunk[0].transcript;
        }
        // 实时显示中间识别结果，让用户知道系统确实在听
        setInterimText((finalText + interim).trim());
      };
      instance.onerror = () => {
        activeRecognition.current = null;
        if (attempt !== voiceAttempt.current) return;
        const cancelled = cancelSendRef.current;
        holdingRef.current = false;
        void (async () => {
          const recording = await settleRecording(cancelled);
          cancelSendRef.current = false;
          setCancelArmed(false);
          setInterimText('');
          setVoicePhase('idle');
          if (recording) {
            // 识别出错，但录下的原话还在 → 留一条能回放的语音，别连录音一起丢
            setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: '', isVoice: true, audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'error' as const }]);
            showHint('语音识别出错，录音已保留，可点击回放；也可以直接打字。');
            return;
          }
          showHint('语音识别出错，请重试，或直接打字。');
        })();
      };
      instance.onend = () => {
        activeRecognition.current = null;
        if (attempt !== voiceAttempt.current) return;
        const transcript = finalText.trim();
        const cancelled = cancelSendRef.current;
        holdingRef.current = false;
        void (async () => {
          // 等并行那一路录音收尾：识别先结束是常态，不能因此把原话弄丢
          const recording = await settleRecording(cancelled);
          setInterimText('');
          // 上滑取消：丢弃，不发
          if (cancelled) {
            cancelSendRef.current = false;
            setCancelArmed(false);
            setVoicePhase('idle');
            showHint('已取消发送。想说的话，按住录音按钮重新发送即可。');
            return;
          }
          if (!transcript) {
            setVoicePhase('idle');
            if (recording) {
              // 没听清，但录上了：留一条能回放的语音，老人可以自己确认刚才说了什么
              setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: '', isVoice: true, audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'error' as const }]);
              showHint('没有听清您说的话，录音已保留，可点击回放；也可以按住按钮重说一遍。');
              return;
            }
            showHint('没有听清您说的话，请按住按钮重说一遍，或直接打字。');
            return;
          }
          // 识别成功：转写文字 + 刚录下的原话一起入列，点语音条就能回放
          // 有图片待发送时，把图片也挂到这条语音气泡上，作为图片说明一起发
          setMessages(items => [...items, {
            id: crypto.randomUUID(), role: 'user', text: transcript, isVoice: true,
            ...(recording ? { audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'done' as const } : {}),
            ...(pendingImages.length ? { imageDataUrls: pendingImages } : {}),
          }]);
          void submitRecognizedText(transcript);
        })();
      };
      setVoicePhase('recording');
      try { instance.start(); }
      catch { holdingRef.current = false; setInterimText(''); setVoicePhase('idle'); showHint('语音识别启动失败，请直接打字。'); return; }
      // 关键：录音和识别同时跑。识别负责转文字，录音负责留下"原话"——
      // 这样即使后端 ASR 用不了、退到浏览器识别，老人的话也照样能回放。
      pendingRecorder.current = startParallelRecording(attempt);
      return;
    }

    if (!navigator.mediaDevices?.getUserMedia) {
      showHint('当前环境无法使用麦克风（需 localhost 或 HTTPS 访问），请直接打字。');
      return;
    }
    setVoicePhase('pressing');
    const attempt = ++voiceAttempt.current;
    holdingRef.current = true;
    pressStart.current = Date.now();
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      // 拿授权期间已经松手 / 又按了一次 → 丢弃这路输入
      if (attempt !== voiceAttempt.current || !holdingRef.current) {
        stream.getTracks().forEach(track => track.stop());
        return;
      }
      streamRef.current = stream;
      const recorder = new MediaRecorder(stream);
      mediaRecorder.current = recorder;
      audioChunks.current = [];
      recorder.ondataavailable = event => { if (event.data.size > 0) audioChunks.current.push(event.data); };
      recorder.start();
      setVoicePhase('recording');
    } catch (error) {
      console.warn('录音失败', error);
      holdingRef.current = false;
      setVoicePhase('idle');
      showHint('麦克风授权被拒绝：请点击地址栏麦克风图标允许，或直接打字发送。');
    }
  };

  /** 松开：已上滑取消则丢弃；否则收尾录音 → 后端 ASR 转写 → 松手直发 */
  const stopVoice = async () => {
    if (!voiceEnabled.asr) {
      // 取消标记必须在松手这一刻同步读出来（handleVoiceRelease 已把 holdingRef 置 false），
      // 再交给 onend 收尾：识别和录音谁先结束都能等到对方。
      const cancelled = cancelSendRef.current;
      if (!cancelled) setVoicePhase('transcribing');
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
      mediaRecorder.current = null;
      streamRef.current = null;
      recorder.stream.getTracks().forEach(track => track.stop());
      try { recorder.stop(); } catch { /* 可能尚未真正开始 */ }
      audioChunks.current = [];
      setVoicePhase('idle');
      showHint('已取消发送。想说的话，按住录音按钮重新发送即可。');
      return;
    }
    // 短按：不足阈值视为误触，丢弃录音不发送
    if (Date.now() - pressStart.current < MIN_HOLD_MS) {
      mediaRecorder.current = null;
      streamRef.current = null;
      recorder.stream.getTracks().forEach(track => track.stop());
      try { recorder.stop(); } catch { /* 可能尚未真正开始 */ }
      audioChunks.current = [];
      setVoicePhase('idle');
      showHint('说话时间太短，请按住按钮说话');
      return;
    }
    mediaRecorder.current = null;
    streamRef.current = null;
    // 先拿到可回放的录音（本地 URL + 真实时长），再做识别：
    // 后面无论识别成功还是失败，这段原话都已经在消息上了，不会被失败请求带走。
    const recording = await takeRecording(recorder);
    if (!recording) { setVoicePhase('idle'); return; }
    const { audioUrl, duration, blob, mimeType } = recording;
    const voiceMsgId = crypto.randomUUID();
    // 有图片待发送时，把图片挂到这条语音气泡上，语音识别结果将作为图片说明一起发
    setMessages(items => [...items, {
      id: voiceMsgId, role: 'user', text: '', isVoice: true,
      audioUrl, audioDuration: duration, voiceState: 'transcribing',
      ...(pendingImages.length ? { imageDataUrls: pendingImages } : {}),
    }]);
    setVoicePhase('transcribing');

    const text = await transcribeAudio(blob, mimeType);
    const trimmed = (text || '').trim();
    if (!trimmed) {
      // 录音已经在气泡上了：识别这一环失败，只影响文字，不影响回放
      setMessages(items => items.map(m => m.id === voiceMsgId ? { ...m, voiceState: 'error' as const } : m));
      setVoicePhase('idle');
      showHint(text === null
        ? '语音识别服务暂时不可用，录音已保留，可点击回放；也可以直接打字。'
        : '没有听清您说的话，录音已保留，可点击回放；请按住按钮重说一遍，或直接打字。');
      return;
    }
    setMessages(items => items.map(m => m.id === voiceMsgId ? { ...m, text: trimmed, voiceState: 'done' as const } : m));
    // 松手直发：识别文本连同上面的语音气泡一起作为"语音消息"送出（气泡可点击回放）
    void submitRecognizedText(trimmed);
  };

  /** 选择图片/拍照：先暂存预览（最多 3 张），可补充说明后手动发送。
   *  按钮始终可见：视觉模型未开启时也照常发送，由后端友好提示处理。 */
  const handleImageUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = '';
    if (!files.length) return;
    const room = MAX_IMAGES - pendingImages.length;
    if (room <= 0) { showHint(`一次最多上传 ${MAX_IMAGES} 张图片`); return; }
    const selected = files.slice(0, room);
    // 先压缩再上传：说明书/化验单走大字档保 OCR，药盒照片走小档省流量，压缩失败自动回退原图。
    const compressed = await Promise.all(selected.map(compressImageFile));
    // 同一张图重复选（连点、同图多拍）没有意义：后端会把它当成几张图各识别一遍，
    // 回复里就会出现好几遍一模一样的描述。按压缩后的内容去重，保留第一次出现的顺序，
    // 已经在待发送列表里的图也不再重复添加。后端还有一层兜底。
    const dataUrls = compressed.filter(
      (url, index) => compressed.indexOf(url) === index && !pendingImages.includes(url),
    );
    if (!dataUrls.length) { showHint('这张图片已经添加过了，不用重复上传。'); return; }
    setPendingImages(prev => [...prev, ...dataUrls].slice(0, MAX_IMAGES));
    if (files.length > room) showHint(`一次最多上传 ${MAX_IMAGES} 张，已保留前 ${MAX_IMAGES} 张。`);
  };

  /** 发送图片：把图片（最多 3 张）当作一轮对话消息发给后端，识别+总结由后端协作完成后回话 */
  const sendPendingImage = async () => {
    if (!pendingImages.length || busy || blockedByClosed()) return;
    if (!conversationId) { showHint('尚未连接对话，请先点"重新连接"。'); return; }
    const hint = input.trim();
    const dataUrls = pendingImages;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: hint, imageDataUrls: dataUrls }]);
    setInput('');
    setPendingImages([]);
    setImageTurn(true);
    setBusy(true);
    try {
      addAssistant(await sendAgentImageMessage(conversationId, dataUrls, hint));
    } catch (error) {
      setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '图片发送失败，请稍后重试。' }]);
    } finally {
      setBusy(false);
      setImageTurn(false);
    }
  };

  const currentVoiceLabel = voices.find(v => v.id === ttsVoice)?.label ?? ttsVoice;
  const holdingVoice = voicePhase === 'recording' || voicePhase === 'pressing';
  /** 这段对话已经结束：只读查看，不能再发消息。 */
  const conversationClosed = conversationStatus === 'CLOSED';

  return <main className="flex min-h-dvh flex-col pb-[180px]">
    <PageHeader title="复诊助手" subtitle="一次只问一件事" onBack={() => onNavigate('home')} />

    <div className="border-b bg-[#fffaf3] px-5 py-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2 text-sm font-semibold text-[#76533d]"><Sparkles className="size-4 shrink-0 text-primary" /><span className="truncate">{conversationClosed ? '这段对话已结束' : `当前步骤：${stageLabels[turn?.stage ?? ''] ?? '正在连接'}`}</span></div>
        <div className="flex shrink-0 items-center gap-2">
          {voiceEnabled.tts && <>
            <button onClick={() => setShowTtsSettings(s => !s)} aria-label="音色和语速设置" className="flex h-12 items-center gap-1 rounded-full border bg-white px-3 text-xs font-bold text-[#76533d] shadow-sm">
              {currentVoiceLabel.split('·')[0].trim()}<ChevronDown className={`size-3.5 transition-transform ${showTtsSettings ? 'rotate-180' : ''}`} />
            </button>
          </>}
          <button onClick={() => void loadHistory()} aria-label="历史对话" className="grid size-12 place-items-center rounded-full border bg-white shadow-sm"><History className="size-6 text-primary" /></button>
          <button onClick={newConversation} aria-label="新对话" className="grid size-12 place-items-center rounded-full border bg-white shadow-sm"><Plus className="size-6 text-primary" /></button>
        </div>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <button onClick={() => void (turn?.stage === 'COMPLETED' || conversationClosed ? begin() : sendAction('CONTINUE', '', '我想预约复诊'))} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary text-base font-bold text-white"><CalendarSearch className="size-5" />{conversationClosed ? '再约一次' : turn?.stage === 'COMPLETED' ? '再次预约' : '预约复诊'}</button>
        <button onClick={() => onNavigate('tasks')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><ClipboardCheck className="size-5 text-primary" />事项查询</button>
      </div>

      {historyOpen && (
        <div className="mt-2 rounded-xl border border-[#dfb98f] bg-white p-2">
          <div className="mb-1 flex items-center justify-between px-1">
            <span className="text-xs font-bold text-[#76533d]">最近 3 天的对话</span>
            <button onClick={() => setHistoryOpen(false)} className="text-xs text-[#a87e5c]">收起</button>
          </div>
          <button onClick={newConversation} className="mb-1 flex w-full items-center gap-2 rounded-lg bg-primary px-3 py-2 text-sm font-bold text-white"><Plus className="size-4" />开启新对话</button>
          {historyLoading && <div className="py-3 text-center text-xs text-[#a87e5c]">加载中…</div>}
          <div className="max-h-56 space-y-1 overflow-y-auto">
            {historyList.map(item => (
              <button key={item.id} onClick={() => void openConversation(item.id)} className={`flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm ${item.id === conversationId ? 'bg-[#f6e3cd] font-bold text-[#6c3d24]' : 'text-[#5c4028] hover:bg-[#fffaf3]'}`}>
                <MessageSquareText className="size-4 shrink-0 opacity-50" />
                <span className="flex-1 truncate">{item.title}</span>
                {item.status === 'CLOSED' && <span className="shrink-0 rounded-full bg-[#eee5da] px-1.5 py-0.5 text-[10px] text-[#8a6a4f]">已结束</span>}
                <span className="shrink-0 text-[10px] opacity-50">{timeAgo(item.updatedAt)}</span>
              </button>
            ))}
            {!historyLoading && !historyList.length && <div className="py-3 text-center text-xs text-[#a87e5c]">最近 3 天还没有其他对话</div>}
          </div>
        </div>
      )}

      {voiceEnabled.tts && showTtsSettings && (
        <div className="mt-2 space-y-3 rounded-xl border border-[#dfb98f] bg-white p-3">
          <div>
            <label className="mb-1 block text-xs font-semibold text-[#76533d]">音色</label>
            <select value={ttsVoice} onChange={e => setTtsVoice(e.target.value)} className="w-full rounded-lg border border-[#dfb98f] bg-white px-2 py-1.5 text-sm text-[#6c3d24]">
              {voices.map(v => <option key={v.id} value={v.id}>{v.label}</option>)}
            </select>
          </div>
          <div>
            <div className="mb-1 flex items-center justify-between">
              <label className="text-xs font-semibold text-[#76533d]">语速</label>
              <span className="text-xs text-[#a87e5c]">{ttsSpeed.toFixed(1)}x</span>
            </div>
            <input type="range" min="0.5" max="2.0" step="0.1" value={ttsSpeed} onChange={e => setTtsSpeed(parseFloat(e.target.value))} className="w-full accent-[#c87a3d]" />
            <div className="flex justify-between text-[10px] text-[#a87e5c]"><span>慢 0.5x</span><span>正常 1.0x</span><span>快 2.0x</span></div>
          </div>
          <div className="flex gap-2">
            <button onClick={() => { setTtsVoice('Cherry'); setTtsSpeed(1.0); }} className="flex-1 rounded-lg border border-[#dfb98f] px-2 py-1.5 text-xs font-semibold text-[#6c3d24]">恢复默认</button>
            <button onClick={() => setShowTtsSettings(false)} className="flex-1 rounded-lg bg-[#c87a3d] px-2 py-1.5 text-xs font-semibold text-white">收起</button>
          </div>
        </div>
      )}
    </div>

    <div className="flex-1 space-y-4 px-5 py-5">
      {/* 已结束的对话：只读。用大白话说清楚，不出现"会话""超时""上限"这类词。 */}
      {conversationClosed && (
        <div className="rounded-2xl border border-[#dfb98f] bg-[#fff8ed] px-4 py-3 text-center text-sm leading-6 font-semibold text-[#8a6a4f]">
          本次对话已结束，上面的内容随时可以查看。<br />
          想接着问，点下面的「开启新对话」就行。
        </div>
      )}

      <section aria-label="对话记录" className="space-y-3">
        {messages.map(message => <ChatBubble key={message.id} message={message} />)}
        {busy && <div className="flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />{imageTurn ? IMAGE_STAGES[imageStage] : '复诊助手正在处理…'}</div>}
      </section>

      {turn?.plan && <PlanCard plan={turn.plan} />}
      {turn?.confirmation && !conversationClosed && <ConfirmationCardView card={turn.confirmation} busy={busy} onConfirm={() => void confirm(true)} onCancel={() => void confirm(false)} />}
      {turn?.result && <ResultCardView result={turn.result} />}
      <ToolTracePanel traces={turnTraces} answer={turn?.reply} />

      {!!turn?.quickReplies.length && !turn.confirmation && !conversationClosed && <section aria-label="快捷回答" className="flex flex-wrap gap-2">
        {turn.quickReplies.slice(0, 4).map(choice => {
          // 语音确认场景：对→✓（绿色），不对→×（红色）
          if (choice.label.includes('对') && choice.action === 'CONTINUE') {
            return <button key={choice.label + choice.action} disabled={busy} onClick={() => void sendAction(choice.action, choice.value, choice.label)} className="grid size-12 place-items-center rounded-full border border-green-400 bg-green-50 text-green-600 shadow-sm disabled:opacity-50"><Check className="size-6" /></button>;
          }
          if (choice.label.includes('不对')) {
            return <button key={choice.label + choice.action} disabled={busy} onClick={() => void sendAction(choice.action, choice.value, choice.label)} className="grid size-12 place-items-center rounded-full border border-red-400 bg-red-50 text-red-600 shadow-sm disabled:opacity-50"><X className="size-6" /></button>;
          }
          return <button key={choice.label + choice.action + choice.value} disabled={busy} onClick={() => void sendAction(choice.action, choice.value, choice.label)} className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm disabled:opacity-50">{choice.label}</button>;
        })}
      </section>}

      {!conversationId && !busy && <button onClick={() => void begin()} className="flex min-h-13 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><RefreshCw className="size-5" />重新连接</button>}
      <div ref={bottomAnchor} aria-hidden="true" className="h-px" />
    </div>

    {/* 已结束的对话不再提供输入框，改成一个「开启新对话」的按钮：老规矩，能做什么就摆什么。 */}
    {conversationClosed ? (
      <div className="fixed bottom-[90px] left-1/2 z-40 w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 rounded-3xl border-2 border-[#dfb98f] bg-[#fffaf3] p-3 text-center shadow-[0_8px_30px_rgb(91_55_32/18%)]">
        <button onClick={newConversation} className="flex min-h-12 w-full items-center justify-center gap-2 rounded-2xl bg-primary text-base font-bold text-white"><Plus className="size-5" />开启新对话</button>
        <p className="mt-1.5 text-xs text-[#a87e5c]">本次对话已结束，有问题可以开一段新的接着问。</p>
      </div>
    ) : (
    <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      {pendingImages.length > 0 && (
        <div className="mb-2 flex flex-wrap items-center gap-2 rounded-2xl bg-[#fff8ed] p-2">
          {pendingImages.map((url, index) => (
            <div key={index} className="relative shrink-0">
              <img src={url} alt={`待发送图片${index + 1}`} className="h-24 w-24 rounded-xl object-cover" />
              <button type="button" onClick={() => setPendingImages(prev => prev.filter((_, i) => i !== index))} aria-label="取消这张图片" className="absolute -right-1.5 -top-1.5 grid size-6 place-items-center rounded-full bg-[#dfb98f] text-[#6c3d24] shadow"><X className="size-3.5" /></button>
            </div>
          ))}
          {pendingImages.length < MAX_IMAGES && (
            <button type="button" onClick={() => fileInputRef.current?.click()} aria-label="继续添加图片" className="grid size-24 shrink-0 place-items-center rounded-xl border border-dashed border-[#dfb98f] text-[#a87e5c]"><Plus className="size-6" /></button>
          )}
        </div>
      )}
      <div className="flex items-center gap-2">
        <button type="button" ref={micButtonRef} aria-label="按下说话" disabled={voicePhase === 'transcribing' || voicePhase === 'sending'} style={{ touchAction: 'none', WebkitUserSelect: 'none', userSelect: 'none' }} onPointerDown={startVoicePress} onPointerMove={handleVoiceMove} onPointerUp={handleVoiceRelease} onPointerCancel={handleVoiceCancel} className={`flex min-h-12 shrink-0 items-center gap-2 rounded-2xl px-3 font-bold disabled:opacity-50 ${holdingVoice ? (cancelArmed ? 'bg-[#fff3cd] text-[#8a6d3b]' : 'bg-red-100 text-red-700') : 'bg-secondary text-primary'}`}><Mic className="size-6" /><span className="hidden min-[380px]:inline">{holdingVoice ? (cancelArmed ? '松开取消' : '松开发送') : '按住说话'}</span></button>
        <input value={input} onChange={event => setInput(event.target.value)} aria-label="输入想说的话" placeholder={pendingImages.length ? '可在此添加辅助说明（可选）' : '也可以在这里打字'} className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none" />
        <button type="button" aria-label="从相册选择图片" onClick={() => fileInputRef.current?.click()} className="grid size-12 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] bg-white text-[#6c3d24]"><Image className="size-5" /></button>
        <button type="button" aria-label="拍照" onClick={() => setCameraOpen(true)} className="grid size-12 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] bg-white text-[#6c3d24]"><Camera className="size-5" /></button>
        <button type="submit" disabled={(!input.trim() && !pendingImages.length) || busy} aria-label="发送" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40">{busy ? <LoaderCircle className="size-5 animate-spin" /> : <Send className="size-5" />}</button>
      </div>
    </form>
    )}

    {/* 用 opacity-0 + absolute 代替 hidden，避免某些浏览器 click() 不生效 */}
    <input ref={fileInputRef} type="file" accept="image/*" multiple className="pointer-events-none absolute h-0 w-0 opacity-0" onChange={handleImageUpload} />

    {/* 语音反馈面板：让老人明确知道"系统到底有没有听到" */}
    {(voicePhase === 'pressing' || voicePhase === 'recording' || voicePhase === 'transcribing' || voicePhase === 'sending') && (
      <div className="fixed bottom-[168px] left-1/2 z-40 w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 rounded-3xl border-2 border-[#dfb98f] bg-[#fffaf3] p-4 text-center shadow-[0_8px_30px_rgb(91_55_32/20%)]">
        {(voicePhase === 'recording' || voicePhase === 'pressing') && (
          <div className="space-y-2">
            <p className="text-lg font-bold text-red-600">{cancelArmed ? '↩️ 松开手指，取消发送' : '🔴 我正在听，您请说'}</p>
            <LevelMeter stream={streamRef.current} active={voicePhase === 'recording'} />
            {!voiceEnabled.asr && interimText && <p className="text-sm font-medium text-[#6c3d24]">“{interimText}”</p>}
            <p className="text-sm text-[#a87e5c]">{cancelArmed ? '已上滑取消，松手就不会发送' : '说完松开按钮，将自动发送'}</p>
          </div>
        )}
        {voicePhase === 'transcribing' && <p className="flex items-center justify-center gap-2 text-base font-semibold text-[#76533d]"><LoaderCircle className="size-5 animate-spin" />正在识别您说的话…</p>}
        {voicePhase === 'sending' && <p className="flex items-center justify-center gap-2 text-base font-semibold text-[#76533d]"><LoaderCircle className="size-5 animate-spin" />正在发送，请稍候…</p>}
      </div>
    )}

    {/* 浮动提示条：3 秒自动消失 */}
    {hint && <div className="fixed bottom-[170px] left-1/2 z-50 w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 rounded-2xl bg-black/80 px-4 py-2.5 text-center text-sm leading-6 text-white shadow-lg">{hint}</div>}

    {/* 真实摄像头拍照弹层 */}
    {cameraOpen && <CameraCapture onClose={() => setCameraOpen(false)} onCapture={dataUrl => {
      setCameraOpen(false);
      // 同一张图重复拍（对着同一盒药连按快门）同样只留一张，避免后端把同一张图识别好几遍。
      if (pendingImages.includes(dataUrl)) { showHint('这张图片已经添加过了，不用重复拍照。'); return; }
      if (pendingImages.length >= MAX_IMAGES) { showHint(`一次最多上传 ${MAX_IMAGES} 张图片`); return; }
      setPendingImages(prev => [...prev, dataUrl]);
    }} />}
  </main>;
}
