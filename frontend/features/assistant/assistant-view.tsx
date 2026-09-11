'use client';

import { FormEvent, useEffect, useRef, useState, type ChangeEvent } from 'react';
import { CalendarSearch, Camera, ClipboardCheck, History, Image as ImageIcon, LoaderCircle, Lock, NotebookPen, Plus, RefreshCw, Send, Sparkles, SquarePen, X } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import {
  closeAgentConversation, confirmAgentActions, getConversationHistory,
  sendAgentAction, sendAgentImageMessage, sendAgentMessage, startConversation,
  type ConversationSummary,
} from '@/lib/agent-api';
import { compressImageFile } from '@/lib/image-compress';
import { speakText, stopPlayback } from '@/lib/tts-player';
import type { VoiceRecording } from '@/features/voice/use-press-to-talk';
import type { AgentPlanCard, AgentTurnResponse, ChatMessage, ConversationHistoryResponse, TabId, TravelFocus, VoicePreference } from '@/types/domain';
import { ConfirmationCardView, PlanCard, ResultCardView } from './assistant-cards';
import { CameraCapture } from './camera-capture';
import { ChatBubble } from './chat-bubble';
import { HistorySheet } from './history-sheet';
import { ToolTracePanel } from './tool-trace-panel';
import { useTurnProgress } from './use-turn-progress';
import { TtsSettings } from './tts-settings';

/** 点一下就填进输入框的示例话：分别对应“记数值 / 记提醒 / 管理已有提醒”三件事。 */
const EXAMPLE_SAYINGS = ['我的血压是100', '明早八点提醒我吃药', '我都有哪些备忘'];

/**
 * 等待时显示的话。图片轮要走视觉识别，实测十几秒，一句「正在处理」老人会以为卡死了；
 * 所以按阶段轮换，让他知道系统在往前走。措辞必须老实——这几步后端真的在依次做，
 * 不是拿来撑场面的假进度条。
 */
const IMAGE_STAGES = ['正在识别图片…', '正在核对信息…', '正在整理结果…'];
const PLAIN_STAGES = ['复诊助手正在处理…'];
/** 每个阶段停留多久。比识别耗时略短，让最后一个阶段在结束前就到位。 */
const STAGE_INTERVAL_MS = 4000;

/** 一轮最多发几张图。后端也只取前 3 张，前端先挡住，别让老人白等。 */
const MAX_IMAGES = 3;

/**
 * 记住上次看到哪一段。刷新、息屏、手滑退回首页再进来，都接着上次聊，
 * 不必让老人把刚才说过的话再说一遍。
 */
const LAST_CONVERSATION_KEY = 'silver-agent-last-conversation';

function savedConversationId(): string {
  try { return window.localStorage.getItem(LAST_CONVERSATION_KEY) ?? ''; } catch { return ''; }
}

function rememberConversationId(id: string) {
  // 无痕模式里 localStorage 会直接抛异常。存不上只是下次要重开一段，不值得打断当前这一轮。
  try { window.localStorage.setItem(LAST_CONVERSATION_KEY, id); } catch { /* 存不上就算了 */ }
}

const stageLabels: Record<string, string> = {
  ASK_HOSPITAL: '确认医院', ASK_DEPARTMENT: '确认科室', ASK_DATE: '确认日期',
  ASK_ALTERNATIVE: '补充偏好', ASK_COMPANION: '陪同安排', ASK_TRAVEL: '出行安排',
  ASK_NOTIFY: '家属通知', ASK_TRANSPORT: '交通方式', READY_TO_PLAN: '检查计划', SELECT_PERIOD: '选择上午或下午', CONFIRM_SLOT: '确认推荐时间', SELECT_SLOT: '选择具体时间', NO_SLOT: '更换时间', CONFLICT: '处理冲突',
  EMERGENCY_PAUSED: '已暂停，请及时求助', PARTIAL: '部分完成', TOOL_ERROR: '需要重试或修改', AWAITING_CONFIRMATION: '等待确认', MEMO_TIME: '补充提醒时间', COMPLETED: '办理完成', CANCELLED: '已取消',
};

export function AssistantView({ active, onNavigate, onOpenTravel, voicePreference, onRegisterSend,
  voicePreferenceBusy = false, voicePreferenceError = '', onSpeechRateChange,
  pendingAsk = '', onAskConsumed }: {
  active: boolean;
  onNavigate: (tab: TabId) => void;
  onOpenTravel: (appointmentId?: string, focus?: TravelFocus, options?: { forceSpeak?: boolean }) => void;
  voicePreference: VoicePreference;
  /** 把“说一句话就发送”的能力交给外层，供全局麦克风在任意页面调用。 */
  onRegisterSend?: (send: ((text: string, recording: VoiceRecording | null) => void) | null) => void;
  /**
   * 别的页面递过来的一句话（事项页的「取消这次复诊」）。
   * 不在自己的页面里直接写库，而是走助手这条唯一入口，由助手出确认卡、老人确认后才动手。
   */
  pendingAsk?: string;
  onAskConsumed?: () => void;
  /** 朗读设置面板的保存态：由外层持有，因为这个偏好是所有页面共用的 */
  voicePreferenceBusy?: boolean;
  voicePreferenceError?: string;
  onSpeechRateChange?: (patch: { speechRate?: number }) => void;
}) {
  const [conversationId, setConversationId] = useState('');
  /** 等待提示的当前阶段。图片轮用它轮换，普通轮恒为一条。 */
  const [stages, setStages] = useState<string[]>(PLAIN_STAGES);
  const [stageIndex, setStageIndex] = useState(0);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [turn, setTurn] = useState<AgentTurnResponse | null>(null);
  const [visiblePlan, setVisiblePlan] = useState<AgentPlanCard | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  /**
   * 这段对话是不是只能翻看了（用户主动结束过）。
   * 结束的会话后端一律拒收新消息、按钮和确认，所以界面必须先把输入口收起来——
   * 让老人打完一整句话再被拒，比一开始就不给他输入框更伤人。
   */
  const [readOnly, setReadOnly] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  /** 每开一段新对话加一，历史浮层下次打开会重新拉一遍列表。 */
  const [historyToken, setHistoryToken] = useState(0);
  // 「查看办理过程」的实时一半：轮询后端此刻正在进行的工具调用。
  const progress = useTurnProgress(conversationId);
  const [choicePage, setChoicePage] = useState(0);
  /** 拍好/选好但还没发出去的图片（data URL），可以补一句说明再发。 */
  const [pendingImages, setPendingImages] = useState<string[]>([]);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [hint, setHint] = useState('');
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const hintTimer = useRef<number | null>(null);
  const bottomAnchor = useRef<HTMLDivElement | null>(null);
  const initialized = useRef(false);
  const planShown = useRef(false);
  // 供 ref 版发送使用的最新值：全局麦克风可能在助手页还没打开时就说话。
  const conversationIdRef = useRef('');
  const busyRef = useRef(false);
  const readOnlyRef = useRef(false);
  const activeRef = useRef(active);
  const voiceReplyPending = useRef(false);
  const sendFromVoiceRef = useRef<(text: string, recording: VoiceRecording | null) => void>(() => {});

  const addAssistant = (response: AgentTurnResponse) => {
    setTurn(response);
    setChoicePage(0);
    setConversationId(response.conversationId);
    if (response.plan && !planShown.current) {
      setVisiblePlan(response.plan);
      planShown.current = true;
    } else {
      setVisiblePlan(null);
    }
    const messageId = crypto.randomUUID();
    setMessages(items => [...items, { id: messageId, role: 'assistant', text: response.reply }]);
    // 只识别已知的页面指令；未知 type 一律安全忽略，不报错也不跳空白页。
    const directive = response.uiDirective;
    if (directive && (directive.type === 'OPEN_TRAVEL' || directive.type === 'SHOW_INSIDE_GUIDE')) {
      // 目标页面会朗读更完整的路线与院内指引，这里不再重复播报本轮回复。
      const inside = directive.type === 'SHOW_INSIDE_GUIDE' || directive.focus === 'inside';
      // 这一轮如果是用户用麦克风问的，地图页必须读出地图详情，即使自动朗读是关的。
      onOpenTravel(directive.appointmentId ?? '', inside ? 'inside' : 'outside',
        { forceSpeak: voiceReplyPending.current });
      voiceReplyPending.current = false;
      return;
    }
    // 优先朗读后端给出的权威口播文本；为空时退回回复正文。
    // 用户主动用语音发起的那一轮必须朗读，哪怕他人在别的页面（助手页只是被 hidden）、
    // 或者关闭了自动朗读；自动朗读开关只约束键盘和按钮触发的普通消息。
    if (voiceReplyPending.current || (activeRef.current && voicePreference.autoSpeakEnabled)) {
      // key 用这条消息自己的 id：气泡上的喇叭靠同一个 key 才知道「正在播的是我这条」。
      void speakText(messageId, response.speechText?.trim() || response.reply, {
        rate: voicePreference.speechRate,
        volume: voicePreference.speechVolume,
      });
    }
    voiceReplyPending.current = false;
  };

  const begin = async () => {
    initialized.current = true;
    planShown.current = false;
    busyRef.current = true;
    setBusy(true);
    setMessages([]);
    setTurn(null);
    setVisiblePlan(null);
    setReadOnly(false);
    setHistoryToken(token => token + 1);
    try {
      const response = await startConversation();
      rememberConversationId(response.conversationId);
      addAssistant(response);
      return response.conversationId;
    } catch {
      setMessages([{ id: 'offline', role: 'assistant', text: '后端服务还没有启动。请先运行 Java 后端，再点“重新连接”。' }]);
      return '';
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  /** 把一段已有的历史摆到界面上。翻看与接着聊都走这里，区别只在 readOnly。 */
  const openHistory = (history: ConversationHistoryResponse) => {
    // 恢复出来的旧对话不该再弹一次当时的计划卡：那是「刚才」的卡片，
    // 现在既没有了上下文，按钮也已经过期。
    planShown.current = true;
    rememberConversationId(history.conversationId);
    setConversationId(history.conversationId);
    setTurn(history.current);
    setVisiblePlan(null);
    setChoicePage(0);
    setReadOnly(history.status === 'CLOSED');
    setMessages(history.messages.map(message => ({
      id: String(message.id), role: message.role, text: message.content,
    })));
  };

  /**
   * 刷新后接着上次聊。
   *
   * 只自动接上「还能说话」的那两段（ACTIVE / EXPIRED）；已经结束的不接——
   * 一进页面就把老人丢进一个只能看不能说的地方，他会以为助手坏了。
   * 拉不回来（后端重启过、会话被清）也照常开一段新的，不提示任何错误。
   */
  const restoreOrBegin = async () => {
    initialized.current = true;
    const saved = savedConversationId();
    if (saved) {
      try {
        const history = await getConversationHistory(saved);
        if (history.status !== 'CLOSED') {
          openHistory(history);
          return;
        }
      } catch { /* 这段读不出来了，按没存过处理 */ }
    }
    await begin();
  };

  // 助手页离开时仍保持挂载（外层只是 hidden），这里不随 active 变化停止播报，
  // 否则切到地图页会把地图页刚发起的路线播报取消掉。
  // 旧的播报由用户主动发起输入或发送消息时的 stopPlayback() 负责停止。
  useEffect(() => {
    if (active && !initialized.current) void restoreOrBegin();
  }, [active]);

  useEffect(() => {
    if (!voicePreference.autoSpeakEnabled) stopPlayback();
  }, [voicePreference.autoSpeakEnabled]);

  useEffect(() => () => stopPlayback(), []);

  useEffect(() => () => { if (hintTimer.current) window.clearTimeout(hintTimer.current); }, []);

  useEffect(() => {
    bottomAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, turn?.stage]);

  // 等待提示逐段前进，只前进不回头：识别真花多久不由前端说了算，
  // 停在最后一段「正在整理结果…」比循环回第一段更像在骗人。
  useEffect(() => {
    if (!busy || stages.length < 2) return;
    const timer = window.setInterval(
      () => setStageIndex(index => Math.min(index + 1, stages.length - 1)), STAGE_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [busy, stages]);

  const deliver = async (id: string, value: string,
                         options?: { isVoice?: boolean; recording?: VoiceRecording | null; images?: string[] }) => {
    stopPlayback();
    const recording = options?.recording ?? null;
    const images = options?.images ?? [];
    // 带图的一轮要等十几秒，换成按阶段轮换的提示；普通轮保持一句话。
    setStages(images.length ? IMAGE_STAGES : PLAIN_STAGES);
    setStageIndex(0);
    // 语音消息把录音挂在气泡上：点一下听到的是老人的原话，不是合成的朗读
    setMessages(items => [...items, {
      id: crypto.randomUUID(), role: 'user', text: value,
      ...(options?.isVoice ? { isVoice: true } : {}),
      ...(recording ? { audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'done' as const } : {}),
      ...(images.length ? { imageDataUrls: images } : {}),
    }]);
    setInput('');
    busyRef.current = true;
    setBusy(true);
    // 实时进度只在这段时间有意义：请求一回来就说明这轮结束了。
    progress.start();
    try {
      // 带图的一轮走 /images：识别结论由后端拼好，value（打字或语音说的那句话）当作随图的说明
      addAssistant(await (images.length
        ? sendAgentImageMessage(id, images, value)
        : sendAgentMessage(id, value)));
    }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '服务暂时不可用，请稍后重试。' }]); }
    finally { busyRef.current = false; setBusy(false); progress.stop(); }
  };

  const sendText = async (raw: string) => {
    const value = raw.trim();
    if (!value || !conversationId || busy) return;
    await deliver(conversationId, value);
  };

  /** 提示条：3 秒自动消失，不打断手上的动作。 */
  const showHint = (message: string) => {
    setHint(message);
    if (hintTimer.current) window.clearTimeout(hintTimer.current);
    hintTimer.current = window.setTimeout(() => setHint(''), 3000);
  };

  /**
   * 「新对话」：先结束旧的一段，再开新的一段。
   *
   * 结束只是把旧的那段落成历史（还能翻看），不是删除——老人回头想找「上次说的那家医院」
   * 仍然找得到。结束失败也不拦住开新对话：旧的那段最多是留在「进行中」，
   * 比让人卡在按钮上动弹不得要好。
   */
  const newConversation = async () => {
    if (busy) return;
    stopPlayback();
    setHistoryOpen(false);
    const previous = conversationId;
    if (previous) {
      try { await closeAgentConversation(previous); } catch { /* 关不掉也照样开新的 */ }
    }
    const id = await begin();
    // 换会话这件事只在界面上说一句，不拼进 reply——
    // 后端每一轮的 reply 与 speechText 必须严格对应，前端往里掺自己的话会破坏这个约定。
    if (id) showHint(previous ? '已开始新的一段对话，之前聊过的都留在历史记录里' : '已开始新的一段对话');
  };

  /** 从历史记录里点开一段。是当前这段就只是合上浮层，不重新加载一遍。 */
  const pickFromHistory = async (summary: ConversationSummary) => {
    setHistoryOpen(false);
    if (summary.conversationId === conversationId) return;
    stopPlayback();
    setBusy(true);
    try {
      openHistory(await getConversationHistory(summary.conversationId));
      showHint(summary.status === 'CLOSED' ? '这是之前的一段对话，还能翻看' : '已切换到之前的一段对话');
    } catch {
      showHint('这段对话没能打开，请稍后再试。');
    } finally {
      setBusy(false);
    }
  };

  /** 选图/拍照：先压缩再暂存预览，同一张图重复选（连点、对着同一盒药连拍）只留一张，
   *  否则后端会把同一张图各识别一遍，回复里出现好几遍一模一样的描述。 */
  const addImages = (compressed: string[]) => {
    const room = MAX_IMAGES - pendingImages.length;
    if (room <= 0) { showHint(`一次最多 ${MAX_IMAGES} 张图片，请先把这张发出去`); return; }
    const fresh = compressed.filter((url, index) =>
      compressed.indexOf(url) === index && !pendingImages.includes(url));
    if (!fresh.length) { showHint('这张图片已经添加过了，不用重复上传。'); return; }
    if (fresh.length > room) showHint(`一次最多 ${MAX_IMAGES} 张，已保留前 ${MAX_IMAGES} 张。`);
    setPendingImages([...pendingImages, ...fresh].slice(0, MAX_IMAGES));
  };

  const onPickedFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    // 先清 value：同一张图连选两次也要能再触发 change
    event.target.value = '';
    if (!files.length) return;
    // 压缩失败会自动回退原图，老人不会因为压不动就传不了
    addImages(await Promise.all(files.slice(0, MAX_IMAGES).map(compressImageFile)));
  };

  /** 发送待发图片：图片就是这一轮的问题，输入框里的字（如果有）当补充说明。 */
  const sendPendingImages = async () => {
    if (!pendingImages.length || !conversationId || busy) return;
    const images = pendingImages;
    setPendingImages([]);
    await deliver(conversationId, input.trim(), { images });
  };

  /** 语音发起的发送：助手页还没打开过时先建立会话，再发送。 */
  const sendFromVoice = async (raw: string, recording: VoiceRecording | null = null) => {
    const value = raw.trim();
    // 没听清的录音不发出去（空消息没有意义），但要在对话里留一条能回放的气泡，
    // 让老人自己确认刚才说了什么，而不是对着"没听清"三个字干着急。
    if (!value) {
      if (recording) {
        setMessages(items => [...items, {
          id: crypto.randomUUID(), role: 'user', text: '', isVoice: true,
          audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'error',
        }]);
      }
      return;
    }
    if (busyRef.current) return;
    // 用户是用麦克风发起的本轮交流，即使关闭了“自动朗读”，也要把这一轮结果读出来；
    // 自动朗读开关只控制键盘和按钮触发的普通回复。
    voiceReplyPending.current = true;
    // 眼前这段已经结束了（或者还没建过）：开口说话显然是要办新的事，
    // 直接开一段新的，而不是把这句原话丢进一个只读会话里换回一句拒绝。
    let id = conversationIdRef.current;
    if (!id || readOnlyRef.current) {
      const reused = !!id;
      id = await begin();
      if (!id) { voiceReplyPending.current = false; return; }
      if (reused) showHint('之前那段对话已经结束，已为您开了一段新的');
    }
    // 手上有待发的图片时，这句话就是图片的补充说明，跟图片一起发出去
    const images = pendingImages;
    setPendingImages([]);
    try { await deliver(id, value, { isVoice: true, recording, images }); }
    finally { voiceReplyPending.current = false; }
  };

  // 每轮渲染后同步 ref，保证注册出去的函数拿到的永远是最新实现。
  useEffect(() => {
    conversationIdRef.current = conversationId;
    busyRef.current = busy;
    readOnlyRef.current = readOnly;
    activeRef.current = active;
    sendFromVoiceRef.current = (text, recording) => { void sendFromVoice(text, recording); };
  });

  // 把“说一句话就发送”注册给外层，全局麦克风在任何页面都能用。
  useEffect(() => {
    if (!onRegisterSend) return;
    onRegisterSend((text, recording) => sendFromVoiceRef.current(text, recording));
    return () => onRegisterSend(null);
  }, [onRegisterSend]);

  // 别的页面递过来的那句话（事项页的「取消这次复诊」）。
  // 等这一轮忙完再发：老人可能在上一次回答还没说完时就按了按钮，
  // 直接丢掉的话他会以为按钮坏了，而这里只有一件事要做，不值得排队。
  useEffect(() => {
    if (!pendingAsk || busy) return;
    onAskConsumed?.();
    sendFromVoiceRef.current(pendingAsk, null);
  }, [pendingAsk, busy, onAskConsumed]);

  const sendAction = async (action: string, value: string, label: string) => {
    if (!conversationId || busy) return;
    stopPlayback();
    if (action === 'OPEN_TASKS') {
      onNavigate('tasks');
      return;
    }
    if (action === 'OPEN_TRAVEL') {
      onOpenTravel(value);
      return;
    }
    // 上一轮如果是图片轮，提示语还停在「正在整理结果…」；按钮轮要把它拨回普通提示。
    setStages(PLAIN_STAGES);
    setStageIndex(0);
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: label }]);
    setBusy(true);
    progress.start();
    try { addAssistant(await sendAgentAction(conversationId, action, value, label)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '操作没有成功，请稍后重试。' }]); }
    finally { setBusy(false); progress.stop(); }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    // 有待发图片时，输入框里的字是图片的说明，点发送就是把图片发出去
    if (pendingImages.length) void sendPendingImages();
    else void sendText(input);
  };

  const confirm = async (approved: boolean) => {
    if (!conversationId || busy || !turn?.confirmation) return;
    stopPlayback();
    setStages(PLAIN_STAGES);
    setStageIndex(0);
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: approved ? '我确认执行这些操作' : '暂不执行' }]);
    setBusy(true);
    progress.start();
    try {
      const response = await confirmAgentActions(conversationId, approved, turn.confirmation.confirmationId);
      addAssistant(response);
      if (response.result) window.dispatchEvent(new Event('silver-agent-appointments-updated'));
    }
    catch { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: '暂时未收到办理结果，请重新连接查看当前进度。' }]); }
    finally { setBusy(false); progress.stop(); }
  };

  return <main className="flex min-h-dvh flex-col pb-[180px]">
    <PageHeader title="复诊助手" subtitle="一次只问一件事" onBack={() => onNavigate('home')}
      onHelp={() => void sendAction('CONTACT_HUMAN', '', '联系人工帮助')} />

    <div className="sticky top-[76px] z-40 border-b bg-[#fffaf3]/95 px-5 py-3 backdrop-blur">
      <div className="flex items-center gap-2">
        <div className="flex min-w-0 flex-1 items-center gap-2 text-sm font-semibold text-[#76533d]">
          <Sparkles className="size-4 shrink-0 text-primary" aria-hidden="true" />
          <span className="truncate">{readOnly ? '这段对话已经结束' : turn?.task?.active ? `办理步骤：${stageLabels[turn.stage] ?? '正在处理'}` : '当前可以自由交流'}</span>
        </div>
        <button type="button" onClick={() => setHistoryOpen(true)}
          className="flex min-h-10 shrink-0 items-center gap-1.5 rounded-xl border border-[#dfb98f] bg-white px-3 text-sm font-bold text-[#6c3d24]">
          <History className="size-4 text-primary" aria-hidden="true" />历史记录
        </button>
        <button type="button" onClick={() => void newConversation()} disabled={busy}
          className="flex min-h-10 shrink-0 items-center gap-1.5 rounded-xl border border-[#dfb98f] bg-white px-3 text-sm font-bold text-[#6c3d24] disabled:opacity-40">
          <SquarePen className="size-4 text-primary" aria-hidden="true" />新对话
        </button>
      </div>
    </div>

    {/* 只读态由前端自己声明，不靠拼后端回复：后端每一轮的 reply 与 speechText
        必须逐字对应（有测试盯着），前端往里加自己的话会破坏这个约定。 */}
    {readOnly && <section className="border-b bg-[#f6f2ec] px-5 py-3">
      <div className="rounded-2xl border border-[#d9d2c7] bg-white px-4 py-3 shadow-sm">
        <p className="flex items-center gap-2 text-base font-bold text-[#6b6255]">
          <Lock className="size-4 shrink-0" aria-hidden="true" />这是之前的一段对话
        </p>
        <p className="mt-1 text-base leading-6 text-muted-foreground">还能往上翻看，但这里已经不能再办事了。</p>
        <button type="button" onClick={() => void newConversation()} disabled={busy}
          className="mt-3 min-h-12 w-full rounded-2xl bg-primary text-base font-bold text-white disabled:opacity-40">
          开始新的一段对话
        </button>
      </div>
    </section>}
    <div className="border-b bg-[#fffaf3] px-5 py-3">
      <div className="grid grid-cols-2 gap-2">
        {/* 只读态下「预约复诊」直接禁用：后端一定会拒收，让它可点只是诱着老人白按一次。 */}
        <button disabled={busy || readOnly} onClick={() => void sendAction('CONTINUE', '', turn?.task?.active ? '继续刚才的办理' : '我想预约复诊')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary text-base font-bold text-white disabled:opacity-40"><CalendarSearch className="size-5" />{turn?.task?.active ? '继续办理' : '预约复诊'}</button>
        <button onClick={() => onNavigate('tasks')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><ClipboardCheck className="size-5 text-primary" />事项查询</button>
      </div>
      {/* 朗读设置就放在两个主按钮下面：老人觉得「念得太快 / 声音不好听」时，
          顺着页面往下看一眼就能找到，不必翻到别的页面去。 */}
      <div className="mt-2">
        <TtsSettings voicePreference={voicePreference} busy={voicePreferenceBusy}
          error={voicePreferenceError} onChange={patch => onSpeechRateChange?.(patch)} />
      </div>
      <div className="mt-2 rounded-2xl border border-dashed border-[#dba976] bg-white px-3 py-2 shadow-sm">
        <p className="flex items-center gap-2 text-sm font-semibold text-[#6c3d24]"><NotebookPen className="size-4 text-primary" aria-hidden="true" />记数值、记提醒、看看有哪些提醒，都可以说一句</p>
        <div className="mt-1.5 flex flex-wrap gap-2">
          {EXAMPLE_SAYINGS.map(saying => (
            <button key={saying} type="button" onClick={() => setInput(saying)} className="min-h-11 rounded-xl bg-[#fff4e2] px-3 text-base font-semibold text-[#6c3d24]">{saying}</button>
          ))}
        </div>
      </div>
    </div>

    {!readOnly && turn?.task?.active && <section className="border-b bg-[#fff4e7] px-5 py-3">
      <div className="rounded-2xl border border-[#e6bc8c] bg-white px-4 py-3 shadow-sm">
        <div className="flex items-start justify-between gap-3"><div><p className="text-sm font-bold text-primary">复诊办理待继续</p><p className="mt-1 text-base font-semibold">{turn.task.summary}</p>{turn.task.missingField && <p className="mt-1 text-sm text-muted-foreground">下一步：确认{turn.task.missingField}</p>}</div><span className="rounded-full bg-[#fff0dc] px-3 py-1 text-sm font-bold text-primary">{turn.task.status === 'PAUSED' ? '已暂停' : '进行中'}</span></div>
        <div className="mt-3 flex gap-2"><button disabled={busy} onClick={() => void sendAction('RETURN_TO_FLOW', '', '继续刚才的办理')} className="min-h-11 flex-1 rounded-xl bg-primary px-3 font-bold text-white">继续办理</button><button disabled={busy} onClick={() => void sendAction('CANCEL_TASK', '', '取消本次办理')} className="min-h-11 flex-1 rounded-xl border px-3 font-semibold">取消本次办理</button></div>
      </div>
    </section>}
    <div className="flex-1 space-y-4 px-5 py-5">
      <section aria-label="对话记录" className="space-y-3">
        {messages.map(message => <ChatBubble key={message.id} message={message} />)}
        {busy && <div aria-live="polite" className="flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />{stages[Math.min(stageIndex, stages.length - 1)]}</div>}
      </section>

      {visiblePlan && <PlanCard plan={visiblePlan} />}
      {/* 结束了的会话不摆确认卡：后端已经不放行，一张按不动的「确认办理」比没有卡片更糟。 */}
      {!readOnly && turn?.confirmation && <ConfirmationCardView card={turn.confirmation} busy={busy} onConfirm={() => void confirm(true)} onCancel={() => void confirm(false)} />}
      {turn?.result && <ResultCardView result={turn.result} partial={turn.stage === 'PARTIAL'} onOpenTravel={onOpenTravel} />}
      {/* 工具调用过程由后端每轮返回（toolTraces），这里原样渲染，不写死任何一条。
          办理中换成实时进度：这时上一轮的 toolTraces 已经不是「正在发生的事」了，
          混在一起会让人误以为旧步骤是本轮在跑的。 */}
      {turn && <ToolTracePanel traces={busy ? [] : turn.toolTraces}
        liveEvents={progress.events} liveActive={busy && progress.active} />}
      {!readOnly && !!turn?.quickReplies.length && !turn.confirmation && <section aria-label="快捷回答" className="flex flex-wrap gap-2">
        {turn.quickReplies.slice(choicePage * 3, choicePage * 3 + 3).map(choice => <button key={choice.label + choice.action + choice.value} disabled={busy} onClick={() => void sendAction(choice.action, choice.value, choice.label)} className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm disabled:opacity-50">{choice.label}</button>)}
      </section>}

      {!readOnly && (turn?.quickReplies.length ?? 0) > 3 && !turn?.confirmation && <button disabled={busy} onClick={() => setChoicePage(page => (page + 1) % Math.ceil((turn?.quickReplies.length ?? 0) / 3))} className="min-h-12 rounded-2xl border bg-white px-4 text-base font-bold">查看更多选项</button>}
      {!conversationId && !busy && <button onClick={() => void begin()} className="flex min-h-13 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><RefreshCw className="size-5" />重新连接</button>}
      <div ref={bottomAnchor} aria-hidden="true" className="h-px" />
    </div>

    {/* 已结束的会话不给输入框：让老人打完一整句话再被拒，比一开始就不给打字更伤人。 */}
    {readOnly ? <div className="fixed bottom-[90px] left-1/2 z-40 w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 rounded-3xl border bg-[#f6f2ec] p-3 text-center shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      <p className="text-base font-semibold text-[#6b6255]">这段对话已经结束了</p>
      <button type="button" onClick={() => void newConversation()} disabled={busy}
        className="mt-2 min-h-12 w-full rounded-2xl bg-primary text-base font-bold text-white disabled:opacity-40">
        开一段新对话
      </button>
    </div> : <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      {/* 待发送的图片先给老人自己看一眼：拍糊了、拍错了，现在还能点掉重拍 */}
      {pendingImages.length > 0 && <div className="mb-2 flex flex-wrap items-center gap-2 rounded-2xl bg-[#fff8ed] p-2">
        {pendingImages.map((url, index) => <div key={url} className="relative shrink-0">
          {/* eslint-disable-next-line next/no-img-element */}
          <img src={url} alt={`待发送图片${index + 1}`} className="size-24 rounded-xl object-cover" />
          <button type="button" onClick={() => setPendingImages(prev => prev.filter(item => item !== url))} aria-label="取消这张图片" className="absolute -right-1.5 -top-1.5 grid size-6 place-items-center rounded-full bg-[#dfb98f] text-[#6c3d24] shadow"><X className="size-3.5" /></button>
        </div>)}
        {pendingImages.length < MAX_IMAGES && <button type="button" onClick={() => fileInputRef.current?.click()} aria-label="继续添加图片" className="grid size-24 shrink-0 place-items-center rounded-xl border border-dashed border-[#dfb98f] text-[#a87e5c]"><Plus className="size-6" /></button>}
      </div>}
      <div className="flex items-center gap-2">
        <input value={input} onChange={event => setInput(event.target.value)} aria-label="输入想说的话" placeholder={pendingImages.length ? '可在此添加说明（可选）' : '也可以在这里打字'} className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none" />
        <button type="button" onClick={() => fileInputRef.current?.click()} disabled={busy} aria-label="从相册选择图片" className="grid size-12 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] bg-white text-[#6c3d24] disabled:opacity-40"><ImageIcon className="size-5" /></button>
        <button type="button" onClick={() => setCameraOpen(true)} disabled={busy} aria-label="拍照" className="grid size-12 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] bg-white text-[#6c3d24] disabled:opacity-40"><Camera className="size-5" /></button>
        <button type="submit" disabled={(!input.trim() && !pendingImages.length) || busy} aria-label="发送" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40"><Send className="size-5" /></button>
      </div>
    </form>}

    {/* 用 opacity-0 + absolute 代替 hidden：部分浏览器对 display:none 的 input 调 click() 不生效 */}
    <input ref={fileInputRef} type="file" accept="image/*" multiple className="pointer-events-none absolute h-0 w-0 opacity-0" onChange={event => void onPickedFiles(event)} />

    {hint && <div className="fixed bottom-[170px] left-1/2 z-50 w-[calc(100%-48px)] max-w-[420px] -translate-x-1/2 rounded-2xl bg-black/80 px-4 py-2.5 text-center text-sm leading-6 text-white shadow-lg">{hint}</div>}

    {cameraOpen && <CameraCapture onClose={() => setCameraOpen(false)} onCapture={dataUrl => {
      setCameraOpen(false);
      addImages([dataUrl]);
    }} />}

    <HistorySheet open={historyOpen} onClose={() => setHistoryOpen(false)} currentId={conversationId}
      refreshToken={historyToken} onPick={summary => void pickFromHistory(summary)} />
  </main>;
}
