'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { getTurnProgress, type TurnProgressEvent } from '@/lib/agent-api';

/**
 * 轮询间隔。取值只跟一件事有关：老人在 400ms 内不会觉得卡，后端一次查询也只是读内存。
 * 再快就纯属浪费——一轮办理本身要十几秒，真正决定观感的是「每调一个工具就冒出来一步」。
 */
const POLL_MS = 400;

/**
 * 一轮办理的实时进度。
 *
 * 只在「发出去、还没回来」这段时间轮询：请求返回就说明这轮结束了，
 * 之后再拉只能是空转。所以由调用方在发送前 start()、在 finally 里 stop()，
 * 而不是自己监听 busy 之类的状态——发送路径有好几条，漏掉一条就会一直轮询。
 *
 * 事件只增不改，afterSeq 保证每次只拿新的；序号由后端单调递增，所以不需要去重。
 */
export function useTurnProgress(conversationId: string) {
  const [events, setEvents] = useState<TurnProgressEvent[]>([]);
  const [active, setActive] = useState(false);
  const timerRef = useRef<number | null>(null);
  const cursorRef = useRef(0);
  /** 轮询是串行的：上一次还没回来就跳过这一拍，免得慢后端下请求越堆越多。 */
  const inflightRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
    abortRef.current?.abort();
    abortRef.current = null;
    inflightRef.current = false;
    setActive(false);
  }, []);

  const tick = useCallback(async () => {
    if (inflightRef.current) return;
    inflightRef.current = true;
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const snapshot = await getTurnProgress(conversationId, cursorRef.current, controller.signal);
      if (snapshot.events.length) {
        cursorRef.current = Math.max(cursorRef.current, ...snapshot.events.map(event => event.seq));
        setEvents(previous => [...previous, ...snapshot.events]);
      }
      setActive(snapshot.active);
    } catch {
      // 进度是「锦上添花」：后端没起、请求被中断都不该弹错误打断老人，
      // 真正的结果仍然由那次 POST 的响应负责呈现。
    } finally {
      inflightRef.current = false;
    }
  }, [conversationId]);

  const start = useCallback(() => {
    stop();
    cursorRef.current = 0;
    setEvents([]);
    setActive(true);
    void tick();
    timerRef.current = window.setInterval(() => void tick(), POLL_MS);
  }, [stop, tick]);

  /** 换会话或卸载时把定时器收掉，否则会在已经销毁的会话上一直拉。 */
  useEffect(() => stop, [stop]);

  return { events, active, start, stop };
}
