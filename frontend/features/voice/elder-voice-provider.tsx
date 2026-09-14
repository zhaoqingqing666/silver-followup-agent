'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import {
  closeAgentConversation, confirmAgentActions, getConversationHistory,
  sendAgentAction, sendAgentImageMessage, sendAgentMessage, startConversation,
  type ConversationSummary,
} from '@/lib/agent-api';
import { speakText, stopPlayback } from '@/lib/tts-player';
import type {
  AgentPlanCard, AgentTurnResponse, ChatMessage, ConversationHistoryResponse, TravelFocus, VoicePreference,
} from '@/types/domain';
import type { VoiceRecording } from './use-press-to-talk';

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

/** 老人端底部导航的四个格子。二级页（记录、备忘）不属于其中任何一格。 */
export type ElderTab = 'home' | 'tasks' | 'assistant' | 'profile';

/** 一次发送里可选的东西：是不是语音发的、要不要附上原话录音、有没有随图的说明。 */
export interface ElderSendOptions {
  isVoice?: boolean;
  recording?: VoiceRecording | null;
  images?: string[];
}

export interface ElderVoiceValue {
  /** 当前会话。为空表示还没建立，第一条语音会自动建一段。 */
  conversationId: string;
  /** 这一轮正在跑。所有入口共用它，避免同一句话被发两次。 */
  busy: boolean;
  /** 当前这段已经结束了（用户主动结束过），只能翻看。 */
  readOnly: boolean;
  messages: ChatMessage[];
  turn: AgentTurnResponse | null;
  /** 只在第一次出现的那张计划卡。 */
  visiblePlan: AgentPlanCard | null;
  /** 语音/网络失败时给老人看的一句话；展示在全局提示条上。 */
  error: string;
  /** 一句短提示（换了对话、切到历史等），3 秒后自动消失。 */
  hint: string;
  /** 助手页挂载时调用：先试着接上上次那段，接不上就开一段新的。 */
  ensureReady: () => void;
  /** 「新对话」：先结束旧的，再开新的。 */
  startNew: () => Promise<void>;
  /** 从历史列表里点开一段。 */
  pickFromHistory: (summary: ConversationSummary) => Promise<void>;
  /** 把一段历史摆到界面上（当前这段也走它）。 */
  loadConversation: (conversationId: string) => Promise<void>;
  /**
   * 发送一句话。**所有页面、所有入口共用这一个方法**——
   * 语音、打字、别的页面递过来的话，最后都落到这里，不存在第二条发送路径。
   */
  send: (raw: string, options?: ElderSendOptions) => Promise<void>;
  /** 点快捷按钮 / 确认卡上的一句话。 */
  sendAction: (action: string, value: string, label: string) => Promise<void>;
  /** 确认门禁：只有这里会真正执行写操作。 */
  confirm: (approved: boolean) => Promise<void>;
  showHint: (message: string) => void;
  dismissError: () => void;
}

const ElderVoiceContext = createContext<ElderVoiceValue | null>(null);

/** 老人端公共语音层。页面里任何地方都用它，不要再各自建一份会话或发送逻辑。 */
export function useElderVoice(): ElderVoiceValue {
  const value = useContext(ElderVoiceContext);
  if (!value) throw new Error('useElderVoice 必须在 ElderVoiceProvider 里使用');
  return value;
}

/** 提示条自动收起的时长。 */
const HINT_MS = 3000;

/**
 * 老人端全局语音与会话层。
 *
 * <p>它把「会话、发送、进行中、最新响应、朗读、页面指令」这几件事从助手页里提出来，
 * 挂在老人端最外层。这样：
 * <ul>
 *   <li>麦克风不再依赖助手页有没有挂载——首页、事项页、我的页、记录二级页都能说话；</li>
 *   <li>助手页只是这些能力的一个**视图**，不再自己持有一份会话，也就不会出现同一句话发两次；</li>
 *   <li>老人端内部切页（含进二级页再回来）不会把会话弄丢，因为这一层根本没被卸载。</li>
 * </ul>
 *
 * <p>页面跳转只认服务端回来的 {@code uiDirective}：前端不解析回复文本，也不按中文关键词猜意图。
 */
export function ElderVoiceProvider({ children, assistantVisible, navigate, openTravel, voicePreference }: {
  children: ReactNode;
  /** 助手页此刻是不是看得见。决定键盘发起的普通回复要不要朗读（自动朗读开关只约束这种）。 */
  assistantVisible: boolean;
  navigate: (tab: ElderTab) => void;
  openTravel: (appointmentId?: string, focus?: TravelFocus, options?: { forceSpeak?: boolean }) => void;
  voicePreference: VoicePreference;
}) {
  const [conversationId, setConversationId] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [turn, setTurn] = useState<AgentTurnResponse | null>(null);
  const [visiblePlan, setVisiblePlan] = useState<AgentPlanCard | null>(null);
  const [busy, setBusy] = useState(false);
  const [readOnly, setReadOnly] = useState(false);
  const [error, setError] = useState('');
  const [hint, setHint] = useState('');

  const initialized = useRef(false);
  const planShown = useRef(false);
  const hintTimer = useRef<number | null>(null);
  // 供异步流程使用的最新值：发送在 await 之后还要读到它们。
  const conversationIdRef = useRef('');
  const busyRef = useRef(false);
  const readOnlyRef = useRef(false);
  /**
   * 这一轮是不是「老人用麦克风问的」。是的话必须朗读，哪怕他人在别的页面、
   * 或者关着自动朗读；自动朗读开关只约束键盘和按钮触发的普通消息。
   */
  const voiceReplyPending = useRef(false);
  /**
   * 请求序号。**只增不减**，每发一轮 +1。
   *
   * <p>响应回来时拿它比对：不是最新那一轮的，就只把回复贴进对话，
   * 绝不执行它带的页面指令、也不朗读——否则老人已经问到下一步了，
   * 一个慢半拍的旧响应会把他拽回上一页。
   */
  const requestSeq = useRef(0);
  const assistantVisibleRef = useRef(assistantVisible);
  const preferenceRef = useRef(voicePreference);

  const showHint = useCallback((message: string) => {
    setHint(message);
    if (hintTimer.current) window.clearTimeout(hintTimer.current);
    if (!message) return;
    hintTimer.current = window.setTimeout(() => setHint(''), HINT_MS);
  }, []);

  useEffect(() => () => { if (hintTimer.current) window.clearTimeout(hintTimer.current); }, []);

  useEffect(() => {
    conversationIdRef.current = conversationId;
    busyRef.current = busy;
    readOnlyRef.current = readOnly;
    assistantVisibleRef.current = assistantVisible;
    preferenceRef.current = voicePreference;
  }, [conversationId, busy, readOnly, assistantVisible, voicePreference]);

  // 自动朗读被关掉时，正在播的那一句也要停——否则老人会以为这个开关坏了。
  useEffect(() => {
    if (!voicePreference.autoSpeakEnabled) stopPlayback();
  }, [voicePreference.autoSpeakEnabled]);

  /** 纯展示：把一条助手消息贴进对话，并按需朗读。不碰页面跳转。 */
  const appendAssistant = useCallback((response: AgentTurnResponse, speak: boolean) => {
    setTurn(response);
    setConversationId(response.conversationId);
    conversationIdRef.current = response.conversationId;
    if (response.plan && !planShown.current) {
      setVisiblePlan(response.plan);
      planShown.current = true;
    } else {
      setVisiblePlan(null);
    }
    const messageId = crypto.randomUUID();
    setMessages(items => [...items, { id: messageId, role: 'assistant', text: response.reply }]);
    if (!speak) return;
    // 优先朗读后端给出的权威口播文本；为空时退回回复正文。
    void speakText(messageId, response.speechText?.trim() || response.reply, {
      rate: preferenceRef.current.speechRate,
      volume: preferenceRef.current.speechVolume,
    });
  }, []);

  /**
   * 收一轮响应：先贴回复，再决定去哪一页。
   *
   * <p>页面指令只认服务端校验过的 {@code uiDirective}，用它带的对象 ID 跳转；
   * 不从回复文字里解析页面或对象。**没有指令就切到助手页**——这一轮的回复
   * 已经拿到了，切过去只是把它显示出来，不会再问一次智能体。
   */
  const settleTurn = useCallback((response: AgentTurnResponse, voice: boolean, stale: boolean) => {
    const speak = voice || (assistantVisibleRef.current && preferenceRef.current.autoSpeakEnabled);
    appendAssistant(response, speak && !stale);
    if (stale) return;
    const directive = response.uiDirective;
    if (directive && (directive.type === 'OPEN_TRAVEL' || directive.type === 'SHOW_INSIDE_GUIDE')) {
      // 目标页面会朗读更完整的路线与院内指引，这里不再重复播报本轮回复。
      const inside = directive.type === 'SHOW_INSIDE_GUIDE' || directive.focus === 'inside';
      // 老人用麦克风问的那一轮，地图页必须读出地图详情，即使自动朗读是关的。
      openTravel(directive.appointmentId ?? '', inside ? 'inside' : 'outside', { forceSpeak: voice });
      return;
    }
    // 未知 type 一律安全忽略（不跳空白页），但也别把人留在当前页面对着一片安静。
    navigate('assistant');
  }, [appendAssistant, navigate, openTravel]);

  /** 建一段新会话。全局只此一处，助手页和麦克风都走它。 */
  const begin = useCallback(async (): Promise<string> => {
    initialized.current = true;
    planShown.current = false;
    setBusy(true);
    busyRef.current = true;
    setMessages([]);
    setTurn(null);
    setVisiblePlan(null);
    setReadOnly(false);
    setError('');
    try {
      const response = await startConversation();
      rememberConversationId(response.conversationId);
      requestSeq.current += 1;
      settleTurn(response, false, false);
      return response.conversationId;
    } catch {
      setError('后端服务还没有启动。请先运行 Java 后端，再点“重新连接”。');
      return '';
    } finally {
      setBusy(false);
      busyRef.current = false;
    }
  }, [settleTurn]);

  /** 把一段已有的历史摆到界面上。翻看与接着聊都走这里，区别只在 readOnly。 */
  const loadHistory = useCallback((history: ConversationHistoryResponse) => {
    // 恢复出来的旧对话不该再弹一次当时的计划卡：那是「刚才」的卡片，按钮也已经过期。
    planShown.current = true;
    rememberConversationId(history.conversationId);
    setConversationId(history.conversationId);
    conversationIdRef.current = history.conversationId;
    setTurn(history.current);
    setVisiblePlan(null);
    setReadOnly(history.status === 'CLOSED');
    setMessages(history.messages.map(message => ({
      id: String(message.id), role: message.role, text: message.content,
    })));
  }, []);

  /**
   * 接上上次那段。只自动接上「还能说话」的两段（ACTIVE / EXPIRED）；已经结束的不接——
   * 一进页面就把老人丢进一个只能看不能说的地方，他会以为助手坏了。
   */
  const ensureReady = useCallback(() => {
    if (initialized.current) return;
    initialized.current = true;
    void (async () => {
      const saved = savedConversationId();
      if (saved) {
        try {
          const history = await getConversationHistory(saved);
          if (history.status !== 'CLOSED') { loadHistory(history); return; }
        } catch { /* 这段读不出来了，按没存过处理 */ }
      }
      await begin();
    })();
  }, [begin, loadHistory]);

  const startNew = useCallback(async () => {
    if (busyRef.current) return;
    stopPlayback();
    const previous = conversationIdRef.current;
    if (previous) {
      try { await closeAgentConversation(previous); } catch { /* 关不掉也照样开新的 */ }
    }
    const id = await begin();
    // 换会话这件事只在界面上说一句，不拼进 reply——
    // 后端每一轮的 reply 与 speechText 必须严格对应，前端往里掺自己的话会破坏这个约定。
    if (id) showHint(previous ? '已开始新的一段对话，之前聊过的都留在历史记录里' : '已开始新的一段对话');
  }, [begin, showHint]);

  const pickFromHistory = useCallback(async (summary: ConversationSummary) => {
    if (summary.conversationId === conversationIdRef.current) return;
    stopPlayback();
    setBusy(true);
    try {
      loadHistory(await getConversationHistory(summary.conversationId));
      showHint(summary.status === 'CLOSED' ? '这是之前的一段对话，还能翻看' : '已切换到之前的一段对话');
    } catch {
      showHint('这段对话没能打开，请稍后再试。');
    } finally {
      setBusy(false);
    }
  }, [loadHistory, showHint]);

  const loadConversation = useCallback(async (id: string) => {
    try { loadHistory(await getConversationHistory(id)); }
    catch { setError('这段对话没能打开，请稍后再试。'); }
  }, [loadHistory]);

  /**
   * 真正发一轮。所有入口的唯一落点。
   *
   * <p>三条规矩写在这里，而不是散在各个调用点：
   * ① 正在进行中就不接新的（防重复发送，也防止老人连按两次）；
   * ② 每轮取一个递增序号，回来时比对，**旧响应不抢导航**；
   * ③ 失败只说失败，不把网络错误说成业务成功。
   */
  const runTurn = useCallback(async (
    id: string, value: string, options: ElderSendOptions,
    call: (conversationId: string, value: string) => Promise<AgentTurnResponse>,
  ) => {
    const voice = options.isVoice === true;
    const images = options.images ?? [];
    const recording = options.recording ?? null;
    setMessages(items => [...items, {
      id: crypto.randomUUID(), role: 'user', text: value,
      ...(voice ? { isVoice: true } : {}),
      ...(recording ? { audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'done' as const } : {}),
      ...(images.length ? { imageDataUrls: images } : {}),
    }]);
    const seq = ++requestSeq.current;
    setBusy(true);
    busyRef.current = true;
    try {
      settleTurn(await call(id, value), voice, seq !== requestSeq.current);
    } catch (cause) {
      // 请求失败绝不能沿用上一轮的指令或朗读：老人这一步没办成，界面不能装作办成了。
      setError(cause instanceof Error ? cause.message : '服务暂时不可用，请稍后重试。');
      setMessages(items => [...items, {
        id: crypto.randomUUID(), role: 'assistant',
        text: cause instanceof Error ? cause.message : '服务暂时不可用，请稍后重试。',
      }]);
    } finally {
      setBusy(false);
      busyRef.current = false;
    }
  }, [settleTurn]);

  const send = async (raw: string, options: ElderSendOptions = {}) => {
    const value = raw.trim();
    // 没听清的录音不发出去（空消息没有意义），但要在对话里留一条能回放的气泡，
    // 让老人自己确认刚才说了什么，而不是对着“没听清”三个字干着急。
    if (!value) {
      const recording = options.recording ?? null;
      if (recording) {
        setMessages(items => [...items, {
          id: crypto.randomUUID(), role: 'user', text: '', isVoice: true,
          audioUrl: recording.audioUrl, audioDuration: recording.duration, voiceState: 'error',
        }]);
      }
      return;
    }
    if (busyRef.current) return;
    // 用户是用麦克风发起的本轮交流，即使关闭“自动朗读”，也要把这一轮结果读出来。
    voiceReplyPending.current = options.isVoice === true;
    try {
      // 眼前这段已经结束了（或者还没建过）：开口说话显然是要办新的事，
      // 直接开一段新的，而不是把这句原话丢进一个只读会话里换回一句拒绝。
      let id = conversationIdRef.current;
      if (!id || readOnlyRef.current) {
        const reused = !!id;
        id = await begin();
        if (!id) return;
        if (reused) showHint('之前那段对话已经结束，已为您开了一段新的');
      }
      const images = options.images ?? [];
      await runTurn(id, value,
        { ...options, isVoice: voiceReplyPending.current },
        (conversation, text) => images.length
          ? sendAgentImageMessage(conversation, images, text)
          : sendAgentMessage(conversation, text));
    } finally {
      voiceReplyPending.current = false;
    }
  };

  const sendAction = useCallback(async (action: string, value: string, label: string) => {
    const id = conversationIdRef.current;
    if (!id || busyRef.current) return;
    stopPlayback();
    if (action === 'OPEN_TASKS') { navigate('tasks'); return; }
    if (action === 'OPEN_TRAVEL') { openTravel(value); return; }
    await runTurn(id, label, {}, (conversation) => sendAgentAction(conversation, action, value, label));
  }, [navigate, openTravel, runTurn]);

  const confirm = useCallback(async (approved: boolean) => {
    const id = conversationIdRef.current;
    if (!id || busyRef.current || !turn?.confirmation) return;
    stopPlayback();
    await runTurn(id, approved ? '我确认执行这些操作' : '暂不执行', {},
      async (conversation) => {
        const response = await confirmAgentActions(conversation, approved, turn.confirmation!.confirmationId);
        if (response.result) window.dispatchEvent(new Event('silver-agent-appointments-updated'));
        return response;
      });
  }, [runTurn, turn]);

  const value: ElderVoiceValue = {
    conversationId, busy, readOnly, messages, turn, visiblePlan, error, hint,
    ensureReady, startNew, pickFromHistory, loadConversation, send, sendAction, confirm,
    showHint, dismissError: () => setError(''),
  };

  return <ElderVoiceContext.Provider value={value}>{children}</ElderVoiceContext.Provider>;
}
