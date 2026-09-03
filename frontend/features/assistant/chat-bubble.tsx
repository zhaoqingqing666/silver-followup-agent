import { Volume2 } from 'lucide-react';
import type { ChatMessage } from '@/types/domain';

export function ChatBubble({ message }: { message: ChatMessage }) {
  const speak = () => {
    if (message.role === 'assistant' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      window.speechSynthesis.speak(new SpeechSynthesisUtterance(message.text));
    }
  };
  return <div className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}>
    <div className={`max-w-[88%] rounded-3xl px-4 py-3 text-[17px] leading-7 ${message.role === 'user'
      ? 'rounded-br-md bg-primary text-primary-foreground'
      : 'rounded-bl-md bg-card shadow-sm ring-1 ring-border'}`}>
      {message.text}
      {message.role === 'assistant' && <button onClick={speak} aria-label="朗读这条回复" className="ml-2 inline-flex align-middle text-primary">
        <Volume2 className="size-5" />
      </button>}
    </div>
  </div>;
}
