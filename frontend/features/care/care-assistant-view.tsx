'use client';

import { FormEvent, useEffect, useRef, useState } from 'react';
import { LoaderCircle, RefreshCw, Send } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import { CARE_CID_BY_ROLE, getCareElders } from '@/lib/care-api';
import { confirmAgentActions, sendAgentAction, sendAgentMessage, startConversation } from '@/lib/agent-api';
import type { AgentPlanCard, AgentTurnResponse, CareElder, CareRole, ChatMessage } from '@/types/domain';
import { ConfirmationCardView, PlanCard, ResultCardView } from '../assistant/assistant-cards';
import { ChatBubble } from '../assistant/chat-bubble';

interface CareAssistantProps {
  actor: CareRole;
  onBack: () => void;
}

const SUGGESTIONS = ['长辈下次复诊是什么时候？', '需要带哪些材料？', '长辈最近的就诊动态', '帮我给长辈留个提醒'];

/** 老人端的话题入口在照护端还没有对应页面，先不渲染，免得点了没反应。 */
const UNSUPPORTED_ACTIONS = ['OPEN_TASKS', 'OPEN_TRAVEL'];

/**
 * 协同照护端助手页：与就诊人端同款对话外观，接的是同一个智能体。
 * 身份由后端按 care_relations 判定，这里只如实传「谁在操作」和「服务哪位长辈」；
 * 会话里的查询、代约和提醒全部以选中的那位长辈为准。
 */
export function CareAssistantView({ actor, onBack }: CareAssistantProps) {
  const caregiverId = CARE_CID_BY_ROLE[actor];
  const [elders, setElders] = useState<CareElder[]>([]);
  const [eldersLoading, setEldersLoading] = useState(true);
  const [eldersError, setEldersError] = useState('');
  const [subject, setSubject] = useState<CareElder | null>(null);
  const [conversationId, setConversationId] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [turn, setTurn] = useState<AgentTurnResponse | null>(null);
  const [visiblePlan, setVisiblePlan] = useState<AgentPlanCard | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const bottomAnchor = useRef<HTMLDivElement | null>(null);
  /** 切换长辈时作废旧请求的回答，避免上一位长辈的话落到这一位名下。 */
  const sessionToken = useRef(0);

  const addAssistant = (response: AgentTurnResponse) => {
    setTurn(response);
    setConversationId(response.conversationId);
    setVisiblePlan(response.plan ?? null);
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text: response.reply }]);
  };

  const fail = (error: unknown, fallback: string) => {
    setMessages(items => [...items, {
      id: crypto.randomUUID(),
      role: 'assistant',
      text: error instanceof Error ? error.message : fallback,
    }]);
  };

  /** 为某位长辈开一段会话：身份两个轴一起交给后端，缺一不可。 */
  const begin = async (elder: CareElder) => {
    const token = ++sessionToken.current;
    setSubject(elder);
    setMessages([]);
    setTurn(null);
    setVisiblePlan(null);
    setConversationId('');
    setBusy(true);
    try {
      const response = await startConversation(elder.elderId, caregiverId);
      if (token === sessionToken.current) addAssistant(response);
    } catch (error) {
      if (token === sessionToken.current) {
        setMessages([{
          id: 'offline',
          role: 'assistant',
          text: error instanceof Error ? error.message : '后端服务还没有启动，请先运行 Java 后端再重试。',
        }]);
      }
    } finally {
      if (token === sessionToken.current) setBusy(false);
    }
  };

  const loadElders = async () => {
    setEldersLoading(true);
    setEldersError('');
    try {
      const list = await getCareElders(caregiverId);
      setElders(list);
      // 只协同一位长辈时直接开始，不让人多点一步。
      if (list.length === 1) await begin(list[0]);
    } catch (cause) {
      setEldersError(cause instanceof Error ? cause.message : '无法读取协同的长辈');
    } finally {
      setEldersLoading(false);
    }
  };

  useEffect(() => {
    void loadElders();
    return () => { sessionToken.current += 1; };
    // 换一个照护者身份就要重新读他名下的长辈
  }, [caregiverId]);

  useEffect(() => {
    bottomAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages.length, turn?.stage]);

  const deliver = async (id: string, value: string) => {
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: value }]);
    setInput('');
    setBusy(true);
    try { addAssistant(await sendAgentMessage(id, value)); }
    catch (error) { fail(error, '服务暂时不可用，请稍后重试。'); }
    finally { setBusy(false); }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = input.trim();
    if (!value || !conversationId || busy) return;
    void deliver(conversationId, value);
  };

  const sendAction = async (action: string, value: string, label: string) => {
    if (!conversationId || busy) return;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: label }]);
    setBusy(true);
    try { addAssistant(await sendAgentAction(conversationId, action, value, label)); }
    catch (error) { fail(error, '操作没有成功，请稍后重试。'); }
    finally { setBusy(false); }
  };

  const confirm = async (approved: boolean) => {
    if (!conversationId || busy || !turn?.confirmation) return;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: approved ? '我确认执行这些操作' : '暂不执行' }]);
    setBusy(true);
    try { addAssistant(await confirmAgentActions(conversationId, approved, turn.confirmation.confirmationId)); }
    catch (error) { fail(error, '暂时未收到办理结果，请重新查看当前进度。'); }
    finally { setBusy(false); }
  };

  const choices = (turn?.quickReplies ?? []).filter(choice => !UNSUPPORTED_ACTIONS.includes(choice.action));

  const renderBody = () => {
    if (eldersLoading) {
      return (
        <section className="grid min-h-64 place-items-center rounded-3xl border bg-card">
          <div className="text-center text-muted-foreground">
            <LoaderCircle className="mx-auto size-8 animate-spin" />
            <p className="mt-3 text-lg">正在读取协同的长辈…</p>
          </div>
        </section>
      );
    }
    if (eldersError) {
      return (
        <section className="rounded-3xl border bg-card p-6 text-center">
          <p className="text-lg">{eldersError}</p>
          <button onClick={() => void loadElders()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white">
            <RefreshCw className="size-5" />重新读取
          </button>
        </section>
      );
    }
    if (elders.length === 0) {
      return (
        <section className="rounded-3xl border border-[#efcda4] bg-[#fff8ed] px-6 py-10 text-center shadow-sm">
          <h2 className="text-2xl font-bold">暂无可协助的长辈</h2>
          <p className="mt-2 text-lg leading-8 text-muted-foreground">绑定家属或志愿者关系后，就能在这里替长辈办事。</p>
        </section>
      );
    }
    if (!subject) {
      // 协同多位长辈：先选定这一轮服务谁，助手才知道查谁、替谁约。
      return (
        <section className="space-y-3">
          <h2 className="text-xl font-bold">这次帮哪位长辈办理？</h2>
          <div className="grid gap-3">
            {elders.map(elder => (
              <button
                key={elder.elderId}
                type="button"
                onClick={() => void begin(elder)}
                className="min-h-14 rounded-2xl border bg-card px-4 text-left text-lg font-semibold shadow-sm active:scale-[0.99]"
              >
                {elder.name}
                {elder.relationship && <span className="ml-2 text-base font-normal text-muted-foreground">{elder.relationship}</span>}
              </button>
            ))}
          </div>
        </section>
      );
    }
    return (
      <>
        <section aria-label="对话记录" className="space-y-3">
          {messages.map(message => <ChatBubble key={message.id} message={message} />)}
          {busy && <div className="flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />协同助手正在处理…</div>}
        </section>

        {visiblePlan && <PlanCard plan={visiblePlan} />}
        {turn?.confirmation && (
          <ConfirmationCardView card={turn.confirmation} busy={busy} onConfirm={() => void confirm(true)} onCancel={() => void confirm(false)} />
        )}
        {turn?.result && <ResultCardView result={turn.result} partial={turn.stage === 'PARTIAL'} />}

        {!!choices.length && !turn?.confirmation && (
          <section aria-label="快捷回答" className="flex flex-wrap gap-2">
            {choices.map(choice => (
              <button
                key={choice.label + choice.action + choice.value}
                disabled={busy}
                onClick={() => void sendAction(choice.action, choice.value, choice.label)}
                className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm disabled:opacity-50"
              >
                {choice.label}
              </button>
            ))}
          </section>
        )}

        {turn?.task?.active && !turn?.confirmation && (
          <button
            disabled={busy}
            onClick={() => void sendAction('CANCEL_TASK', '', '取消本次办理')}
            className="min-h-12 w-full rounded-2xl border bg-white text-base font-semibold text-muted-foreground"
          >
            取消本次办理
          </button>
        )}

        {!conversationId && !busy && (
          <button onClick={() => void begin(subject)} className="flex min-h-13 w-full items-center justify-center gap-2 rounded-2xl border bg-white text-base font-bold">
            <RefreshCw className="size-5" />重新连接
          </button>
        )}
      </>
    );
  };

  return (
    <main className="flex min-h-dvh flex-col pb-[180px]">
      <PageHeader title="助手" subtitle={subject ? `正在协助 ${subject.name}` : undefined} onBack={onBack} hideHelp />

      <div className="flex-1 space-y-4 px-5 py-5">
        {renderBody()}

        {elders.length > 1 && subject && (
          <section aria-label="切换长辈" className="flex flex-wrap gap-2 pt-1">
            {elders.map(elder => (
              <button
                key={elder.elderId}
                type="button"
                disabled={busy || elder.elderId === subject.elderId}
                onClick={() => void begin(elder)}
                className={`min-h-11 rounded-2xl border px-4 text-base font-semibold shadow-sm disabled:bg-[#fff0dc] disabled:text-primary ${elder.elderId === subject.elderId ? '' : 'bg-white text-[#6c3d24]'}`}
              >
                {elder.name}
              </button>
            ))}
          </section>
        )}

        {subject && (
          <section aria-label="示例提问" className="flex flex-wrap gap-2 pt-1">
            {SUGGESTIONS.map(suggestion => (
              <button
                key={suggestion}
                type="button"
                onClick={() => setInput(suggestion)}
                className="min-h-12 rounded-2xl border border-[#dfb98f] bg-white px-4 text-base font-semibold text-[#6c3d24] shadow-sm active:scale-95"
              >
                {suggestion}
              </button>
            ))}
          </section>
        )}

        <div ref={bottomAnchor} aria-hidden="true" className="h-px" />
      </div>

      {subject && (
        <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 flex w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 items-center gap-2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
          <input
            value={input}
            onChange={event => setInput(event.target.value)}
            aria-label="输入想说的话"
            placeholder="也可以在这里打字"
            className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none"
          />
          <button type="submit" disabled={!input.trim() || busy} aria-label="发送" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40">
            <Send className="size-5" />
          </button>
        </form>
      )}
    </main>
  );
}
