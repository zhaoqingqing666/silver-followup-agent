'use client';

import { FormEvent, useState } from 'react';
import { Mic, Send } from 'lucide-react';
import { PageHeader } from '@/components/common/page-header';
import type { CareRole, ChatMessage } from '@/types/domain';
import { ChatBubble } from '../assistant/chat-bubble';

interface CareAssistantProps {
  actor: CareRole;
  onBack: () => void;
}

const ROLE_GREETING: Record<CareRole, string> = {
  FAMILY: '您好，我是协同助手，帮您跟进长辈的复诊安排与就诊进展。',
  VOLUNTEER: '您好，我是协同助手，协助您做好社区长辈的复诊安排与进展跟进。',
};

const SUGGESTIONS = ['长辈下次复诊是什么时候？', '需要带哪些材料？', '长辈最近的就诊动态'];

const DEMO_REPLY = '已收到您的提问。当前是演示对话，正式的回答功能将在后续版本开放。';
const VOICE_NOTE = '语音输入暂不可用，当前为演示界面，请用文字输入。';

/** 协同照护端助手页：与就诊人端复诊助手同款对话外观，仅演示、不真正回复。 */
export function CareAssistantView({ actor, onBack }: CareAssistantProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([
    { id: 'greet-1', role: 'assistant', text: ROLE_GREETING[actor] },
    { id: 'greet-2', role: 'assistant', text: '把想说的话发给我试试，比如“长辈下次复诊是什么时候”。' },
  ]);
  const [input, setInput] = useState('');

  const appendAssistant = (text: string) => {
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'assistant', text }]);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = input.trim();
    if (!value) return;
    setMessages(items => [...items, { id: crypto.randomUUID(), role: 'user', text: value }]);
    setInput('');
    appendAssistant(DEMO_REPLY);
  };

  return (
    <main className="flex min-h-dvh flex-col pb-[188px]">
      <PageHeader title="助手" onBack={onBack} hideHelp />

      <div className="flex-1 space-y-4 px-5 py-5">
        <section aria-label="对话记录" className="space-y-3">
          {messages.map(message => <ChatBubble key={message.id} message={message} />)}
        </section>

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
      </div>

      <form onSubmit={submit} className="fixed bottom-[90px] left-1/2 z-40 flex w-[calc(100%-32px)] max-w-[448px] -translate-x-1/2 items-center gap-2 rounded-3xl border bg-card p-2 shadow-[0_8px_30px_rgb(91_55_32/18%)]">
        <button
          type="button"
          aria-label="语音输入（演示不可用）"
          onClick={() => appendAssistant(VOICE_NOTE)}
          className="flex min-h-12 shrink-0 items-center gap-2 rounded-2xl bg-secondary px-3 font-bold text-primary"
        >
          <Mic className="size-6" /><span className="hidden min-[380px]:inline">按住说话</span>
        </button>
        <input
          value={input}
          onChange={event => setInput(event.target.value)}
          aria-label="输入想说的话"
          placeholder="也可以在这里打字"
          className="min-w-0 flex-1 bg-transparent px-2 text-base outline-none"
        />
        <button type="submit" disabled={!input.trim()} aria-label="发送（演示）" className="grid size-12 shrink-0 place-items-center rounded-2xl bg-primary text-white disabled:opacity-40">
          <Send className="size-5" />
        </button>
      </form>
    </main>
  );
}
