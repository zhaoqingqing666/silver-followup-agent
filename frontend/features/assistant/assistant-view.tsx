'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { CalendarSearch, ClipboardCheck, LoaderCircle, RefreshCw, Send, Sparkles } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { confirmAgentActions, sendAgentAction, sendAgentMessage, startConversation } from '@/lib/agent-api';
import { speakText, stopSpeech } from '@/lib/speech-service';
import type { AgentPlanCard, AgentTurnResponse, ChatMessage, TabId, TravelFocus, VoicePreference } from '@/types/domain';
import { ConfirmationCardView, PlanCard, ResultCardView } from './assistant-cards';
import { ChatBubble } from './chat-bubble';

const stageLabels: Record<string, string> = {
  ASK_HOSPITAL: '确认医院', ASK_DEPARTMENT: '确认科室', ASK_DATE: '确认日期',
  ASK_ALTERNATIVE: '补充偏好', ASK_COMPANION: '陪同安排', ASK_TRAVEL: '出行安排',
  ASK_NOTIFY: '家属通知', ASK_TRANSPORT: '交通方式', READY_TO_PLAN: '检查计划', SELECT_PERIOD: '选择上午或下午', CONFIRM_SLOT: '确认推荐时间', SELECT_SLOT: '选择具体时间', NO_SLOT: '更换时间', CONFLICT: '处理冲突',
  EMERGENCY_PAUSED: '已暂停，请及时求助', PARTIAL: '部分完成', TOOL_ERROR: '需要重试或修改', AWAITING_CONFIRMATION: '等待确认', COMPLETED: '办理完成', CANCELLED: '已取消',
};

export function AssistantView({ active, onNavigate, onOpenTravel, voicePreference, onRegisterSend }: {
  active: boolean;
  onNavigate: (tab: TabId) => void;
  onOpenTravel: (appointmentId?: string, focus?: TravelFocus, options?: { forceSpeak?: boolean }) => void;
  voicePreference: VoicePreference;
  /** 把“说一句话就发送”的能力交给外层，供全局麦克风在任意页面调用。 */
  onRegisterSend?: (send: ((text: string) => void) | null) => void;
}) {
  const [conversationId, setConversationId] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [turn, setTurn] = useState<AgentTurnResponse | null>(null);
  const [visiblePlan, setVisiblePlan] = useState<AgentPlanCard | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [choicePage, setChoicePage] = useState(0);
  const bottomAnchor = useRef<HTMLDivElement | null>(null);
  const initialized = useRef(false);
  const planShown = useRef(false);
  // 供 ref 版发送使用的最新值：全局麦克风可能在助手页还没打开时就说话。
  const conversationIdRef = useRef('');
  const busyRef = useRef(false);
  const activeRef = useRef(active);
  const voiceReplyPending = useRef(false);
  const sendFromVoiceRef = useRef<(text: string) => void>(() => {});

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
      speakText(response.speechText?.trim() || response.reply, messageId, {
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
    try {
      const response = await startConversation();
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

  // 助手页离开时仍保持挂载（外层只是 hidden），这里不随 active 变化停止播报，
  // 否则切到地图页会把地图页刚发起的路线播报取消掉。
  // 旧的播报由用户主动发起输入或发送消息时的 stopSpeech() 负责停止。
  useEffect(() => {
    if (active && !initialized.current) void begin();
  }, [active]);

  useEffect(() => {
    if (!voicePreference.autoSpeakEnabled) stopSpeech();
  }, [voicePreference.autoSpeakEnabled]);

  useEffect(() => () => stopSpeech(), []);

  useEffect(() => {
    bottomAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, turn?.stage]);

  const deliver = async (id: string, value: string) => {
    stopSpeech();
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: value }]);
    setInput('');
    busyRef.current = true;
    setBusy(true);
    try { addAssistant(await sendAgentMessage(id, value)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '服务暂时不可用，请稍后重试。' }]); }
    finally { busyRef.current = false; setBusy(false); }
  };

  const sendText = async (raw: string) => {
    const value = raw.trim();
    if (!value || !conversationId || busy) return;
    await deliver(conversationId, value);
  };

  /** 语音发起的发送：助手页还没打开过时先建立会话，再发送。 */
  const sendFromVoice = async (raw: string) => {
    const value = raw.trim();
    if (!value || busyRef.current) return;
    // 用户是用麦克风发起的本轮交流，即使关闭了“自动朗读”，也要把这一轮结果读出来；
    // 自动朗读开关只控制键盘和按钮触发的普通回复。
    voiceReplyPending.current = true;
    const id = conversationIdRef.current || await begin();
    if (!id) { voiceReplyPending.current = false; return; }
    try { await deliver(id, value); }
    finally { voiceReplyPending.current = false; }
  };

  // 每轮渲染后同步 ref，保证注册出去的函数拿到的永远是最新实现。
  useEffect(() => {
    conversationIdRef.current = conversationId;
    busyRef.current = busy;
    activeRef.current = active;
    sendFromVoiceRef.current = text => { void sendFromVoice(text); };
  });

  // 把“说一句话就发送”注册给外层，全局麦克风在任何页面都能用。
  useEffect(() => {
    if (!onRegisterSend) return;
    onRegisterSend(text => sendFromVoiceRef.current(text));
    return () => onRegisterSend(null);
  }, [onRegisterSend]);

  const sendAction = async (action: string, value: string, label: string) => {
    if (!conversationId || busy) return;
    stopSpeech();
    if (action === 'OPEN_TASKS') {
      onNavigate('tasks');
      return;
    }
    if (action === 'OPEN_TRAVEL') {
      onOpenTravel(value);
      return;
    }
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: label }]);
    setBusy(true);
    try { addAssistant(await sendAgentAction(conversationId, action, value, label)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '操作没有成功，请稍后重试。' }]); }
    finally { setBusy(false); }
  };

  const submit = (event: FormEvent) => { event.preventDefault(); void sendText(input); };

  const confirm = async (approved: boolean) => {
    if (!conversationId || busy || !turn?.confirmation) return;
    stopSpeech();
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: approved ? '我确认执行这些操作' : '暂不执行' }]);
    setBusy(true);
    try {
      const response = await confirmAgentActions(conversationId, approved, turn.confirmation.confirmationId);
      addAssistant(response);
      if (response.result) window.dispatchEvent(new Event('silver-agent-appointments-updated'));
    }
    catch { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: '暂时未收到办理结果，请重新连接查看当前进度。' }]); }
    finally { setBusy(false); }
  };

  return <main className="flex min-h-dvh flex-col pb-[180px]">
    <PageHeader title="复诊助手" subtitle="一次只问一件事" onBack={() => onNavigate('home')}
      onHelp={() => void sendAction('CONTACT_HUMAN', '', '联系人工帮助')} />

    <div className="sticky top-[76px] z-40 border-b bg-[#fffaf3]/95 px-5 py-3 backdrop-blur">
      <div className="flex items-center gap-2 text-sm font-semibold text-[#76533d]"><Sparkles className="size-4 text-primary" />{turn?.task?.active ? `办理步骤：${stageLabels[turn.stage] ?? '正在处理'}` : '当前可以自由交流'}</div>
    </div>
    <div className="border-b bg-[#fffaf3] px-5 py-3">
      <div className="grid grid-cols-2 gap-2">
        <button onClick={() => void sendAction('CONTINUE', '', turn?.task?.active ? '继续刚才的办理' : '我想预约复诊')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary text-base font-bold text-white"><CalendarSearch className="size-5" />{turn?.task?.active ? '继续办理' : '预约复诊'}</button>
        <button onClick={() => onNavigate('tasks')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><ClipboardCheck className="size-5 text-primary" />事项查询</button>
      </div>
    </div>

    {turn?.task?.active && <section className="border-b bg-[#fff4e7] px-5 py-3">
      <div className="rounded-2xl border border-[#e6bc8c] bg-white px-4 py-3 shadow-sm">
        <div className="flex items-start justify-between gap-3"><div><p className="text-sm font-bold text-primary">复诊办理待继续</p><p className="mt-1 text-base font-semibold">{turn.task.summary}</p>{turn.task.missingField && <p className="mt-1 text-sm text-muted-foreground">下一步：确认{turn.task.missingField}</p>}</div><span className="rounded-full bg-[#fff0dc] px-3 py-1 text-sm font-bold text-primary">{turn.task.status === 'PAUSED' ? '已暂停' : '进行中'}</span></div>
        <div className="mt-3 flex gap-2"><button disabled={busy} onClick={() => void sendAction('RETURN_TO_FLOW', '', '继续刚才的办理')} className="min-h-11 flex-1 rounded-xl bg-primary px-3 font-bold text-white">继续办理</button><button disabled={busy} onClick={() => void sendAction('CANCEL_TASK', '', '取消本次办理')} className="min-h-11 flex-1 rounded-xl border px-3 font-semibold">取消本次办理</button></div>
      </div>
    </section>}
    <div className="flex-1 space-y-4 px-5 py-5">
      <section aria-label="对话记录" className="space-y-3">
        {messages.map(message => <ChatBubble key={message.id} message={message} />)}
        {busy && <div className="flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />复诊助手正在处理…</div>}
      </section>

      {visiblePlan && <PlanCard plan={visiblePlan} />}
      {turn?.confirmation && <ConfirmationCardView card={turn.confirmation} busy={busy} onConfirm={() => void confirm(true)} onCancel={() => void confirm(false)} />}
      {turn?.result && <ResultCardView result={turn.result} partial={turn.stage === 'PARTIAL'} onOpenTravel={onOpenTravel} />}
      {!!turn?.quickReplies.length && !turn.confirmation && <section aria-label="快捷回答" className="flex flex-wrap gap-2">
        {turn.quickReplies.slice(choicePage * 3, choicePage * 3 + 3).map(choice => <button key={choice.label + choice.action + choice.value} disabled={busy} onClick={() => void sendAction(choice.action, choice.value, choice.label)} className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm disabled:opacity-50">{choice.label}</button>)}
      </section>}

      {(turn?.quickReplies.length ?? 0) > 3 && !turn?.confirmation && <button disabled={busy} onClick={() => setChoicePage(page => (page + 1) % Math.ceil((turn?.quickReplies.length ?? 0) / 3))} className="min-h-12 rounded-2xl border bg-white px-4 text-base font-bold">查看更多选项</button>}
      {!conversationId && !busy && <button onClick={() => void begin()} className="flex min-h-13 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><RefreshCw className="size-5" />重新连接</button>}
      <div ref={bottomAnchor} aria-hidden="true" className="h-px" />
    </div>

    <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 flex w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 items-center gap-2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      <input value={input} onChange={event => setInput(event.target.value)} aria-label="输入想说的话" placeholder="也可以在这里打字" className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none" />
      <button type="submit" disabled={!input.trim() || busy} aria-label="发送" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40"><Send className="size-5" /></button>
    </form>
  </main>;
}
