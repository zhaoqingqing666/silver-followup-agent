'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { CalendarSearch, ClipboardCheck, LoaderCircle, Mic, RefreshCw, Send, Sparkles } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { confirmAgentActions, getConversationHistory, sendAgentAction, sendAgentMessage, startConversation } from '@/lib/agent-api';
import type { AgentTurnResponse, ChatMessage, TabId } from '@/types/domain';
import { ConfirmationCardView, PlanCard, ResultCardView } from './assistant-cards';
import { ChatBubble } from './chat-bubble';
import { ToolTracePanel } from './tool-trace-panel';

const CONVERSATION_KEY = 'silver-agent-current-conversation';

interface SpeechRecognitionLike {
  lang: string;
  interimResults: boolean;
  onresult: ((event: { results: ArrayLike<{ 0: { transcript: string } }> }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
}

const stageLabels: Record<string, string> = {
  ASK_HOSPITAL: '确认医院', ASK_DEPARTMENT: '确认科室', ASK_DATE: '确认日期',
  ASK_ALTERNATIVE: '补充偏好', ASK_COMPANION: '陪同安排', ASK_TRAVEL: '出行安排',
  ASK_NOTIFY: '家属通知', ASK_TRANSPORT: '交通方式', READY_TO_PLAN: '检查计划', SELECT_PERIOD: '选择上午或下午', CONFIRM_SLOT: '确认推荐时间', SELECT_SLOT: '选择具体时间', NO_SLOT: '更换时间', CONFLICT: '处理冲突',
  AWAITING_CONFIRMATION: '等待确认', COMPLETED: '办理完成', CANCELLED: '已取消',
};

export function AssistantView({ onNavigate }: { onNavigate: (tab: TabId) => void }) {
  const [conversationId, setConversationId] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [turn, setTurn] = useState<AgentTurnResponse | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(true);
  const [listening, setListening] = useState(false);
  const recognition = useRef<SpeechRecognitionLike | null>(null);
  const bottomAnchor = useRef<HTMLDivElement | null>(null);

  const addAssistant = (response: AgentTurnResponse) => {
    setTurn(response);
    setConversationId(response.conversationId);
    window.localStorage.setItem(CONVERSATION_KEY, response.conversationId);
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: response.reply }]);
  };

  const begin = async () => {
    setBusy(true);
    setMessages([]);
    setTurn(null);
    try { addAssistant(await startConversation()); }
    catch { setMessages([{ id: 'offline', role: 'assistant', text: '后端服务还没有启动。请先运行 Java 后端，再点“重新连接”。' }]); }
    finally { setBusy(false); }
  };

  useEffect(() => {
    const restore = async () => {
      const savedId = window.localStorage.getItem(CONVERSATION_KEY);
      if (!savedId) {
        await begin();
        return;
      }
      setBusy(true);
      try {
        const history = await getConversationHistory(savedId);
        setConversationId(history.conversationId);
        setTurn(history.current);
        setMessages(history.messages.map(message => ({
          id: String(message.id),
          role: message.role,
          text: message.content,
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

  const sendText = async (raw: string) => {
    const value = raw.trim();
    if (!value || !conversationId || busy) return;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: value }]);
    setInput('');
    setBusy(true);
    try { addAssistant(await sendAgentMessage(conversationId, value)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '服务暂时不可用，请稍后重试。' }]); }
    finally { setBusy(false); }
  };

  const sendAction = async (action: string, value: string, label: string) => {
    if (!conversationId || busy) return;
    if (action === 'OPEN_TASKS') {
      onNavigate('tasks');
      return;
    }
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: label }]);
    setBusy(true);
    try { addAssistant(await sendAgentAction(conversationId, action, value)); }
    catch (error) { setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: error instanceof Error ? error.message : '操作没有成功，请稍后重试。' }]); }
    finally { setBusy(false); }
  };

  const submit = (event: FormEvent) => { event.preventDefault(); void sendText(input); };

  const confirm = async (approved: boolean) => {
    if (!conversationId || busy) return;
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

  const startVoice = () => {
    const Constructor = (window as unknown as { webkitSpeechRecognition?: new () => SpeechRecognitionLike }).webkitSpeechRecognition;
    if (!Constructor) { setInput('我想预约复诊'); return; }
    const instance = new Constructor();
    recognition.current = instance;
    instance.lang = 'zh-CN';
    instance.interimResults = false;
    instance.onresult = event => setInput(event.results[0][0].transcript);
    instance.onend = () => setListening(false);
    setListening(true);
    instance.start();
  };

  const stopVoice = () => {
    recognition.current?.stop();
    recognition.current = null;
    setListening(false);
  };

  return <main className="flex min-h-dvh flex-col pb-[180px]">
    <PageHeader title="复诊助手" subtitle="一次只问一件事" onBack={() => onNavigate('home')} />

    <div className="border-b bg-[#fffaf3] px-5 py-3">
      <div className="flex items-center gap-2 text-sm font-semibold text-[#76533d]"><Sparkles className="size-4 text-primary" />当前步骤：{stageLabels[turn?.stage ?? ''] ?? '正在连接'}</div>
      <div className="mt-3 grid grid-cols-2 gap-2">
        <button onClick={() => void (turn?.stage === 'COMPLETED' ? begin() : sendAction('CONTINUE', '', '我想预约复诊'))} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl bg-primary text-base font-bold text-white"><CalendarSearch className="size-5" />{turn?.stage === 'COMPLETED' ? '再次预约' : '预约复诊'}</button>
        <button onClick={() => onNavigate('tasks')} className="flex min-h-12 items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><ClipboardCheck className="size-5 text-primary" />事项查询</button>
      </div>
    </div>

    <div className="flex-1 space-y-4 px-5 py-5">
      <section aria-label="对话记录" className="space-y-3">
        {messages.map(message => <ChatBubble key={message.id} message={message} />)}
        {busy && <div className="flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />复诊助手正在处理…</div>}
      </section>

      {turn?.plan && <PlanCard plan={turn.plan} />}
      {turn?.confirmation && <ConfirmationCardView card={turn.confirmation} busy={busy} onConfirm={() => void confirm(true)} onCancel={() => void confirm(false)} />}
      {turn?.result && <ResultCardView result={turn.result} />}
      {turn && <ToolTracePanel traces={turn.toolTraces} />}

      {!!turn?.quickReplies.length && !turn.confirmation && <section aria-label="快捷回答" className="flex flex-wrap gap-2">
        {turn.quickReplies.slice(0, 4).map(choice => <button key={choice.label + choice.action + choice.value} disabled={busy} onClick={() => void sendAction(choice.action, choice.value, choice.label)} className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm disabled:opacity-50">{choice.label}</button>)}
      </section>}

      {!conversationId && !busy && <button onClick={() => void begin()} className="flex min-h-13 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold"><RefreshCw className="size-5" />重新连接</button>}
      <div ref={bottomAnchor} aria-hidden="true" className="h-px" />
    </div>

    <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 flex w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 items-center gap-2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
      <button type="button" aria-label="按住说话" onPointerDown={startVoice} onPointerUp={stopVoice} onPointerCancel={stopVoice} className={`flex min-h-12 shrink-0 items-center gap-2 rounded-2xl px-3 font-bold ${listening ? 'bg-red-100 text-red-700' : 'bg-secondary text-primary'}`}><Mic className="size-6" /><span className="hidden min-[380px]:inline">{listening ? '正在听…' : '按住说话'}</span></button>
      <input value={input} onChange={event => setInput(event.target.value)} aria-label="输入想说的话" placeholder="也可以在这里打字" className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none" />
      <button type="submit" disabled={!input.trim() || busy} aria-label="发送" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40"><Send className="size-5" /></button>
    </form>
  </main>;
}
