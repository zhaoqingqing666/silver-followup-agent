'use client';

import { useCallback, type RefObject } from 'react';
import { stopPlayback } from '@/lib/tts-player';
import { useElderVoice } from './elder-voice-provider';
import { VoiceMicButton } from './voice-mic-button';
import type { VoiceRecording } from './use-press-to-talk';

/**
 * 老人端的全局麦克风。
 *
 * <p>它挂在公共层而不是助手页里，所以首页、事项页、我的页、记录二级页、地图页
 * 都能直接说话——**老人从来没进过助手页也能用**：发送时发现还没有会话，
 * 会自动建立一段再发出去。
 *
 * <p>两条口令顺序：先问当前页面自己认不认（返回、重听这类只读口令），
 * 页面接不住的才交给主智能体。页面口令是本地短动作，不打断正在进行的对话；
 * 其余一律由后端主模型理解，前端不按中文关键词猜意图。
 */
export function ElderVoiceDock({ pageVoiceRef }: {
  pageVoiceRef: RefObject<((text: string) => boolean) | null>;
}) {
  const voice = useElderVoice();

  const onTranscript = useCallback((text: string, recording: VoiceRecording | null) => {
    if (pageVoiceRef.current?.(text)) return;
    void voice.send(text, { isVoice: true, recording });
  }, [voice, pageVoiceRef]);

  return (
    // 按住就要停掉正在播的朗读：一是老人开口多半是要说新的事，二是麦克风会把
    // 喇叭里的声音一起收进去。捕获阶段先停，不挡按钮自己的指针处理。
    <div onPointerDownCapture={() => stopPlayback()}>
      <VoiceMicButton onTranscript={onTranscript} />
    </div>
  );
}
