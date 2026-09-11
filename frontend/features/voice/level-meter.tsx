'use client';

import { useEffect, useRef } from 'react';

/** 音量条默认根数与高度区间：8px 是静音时的静止高度，36px 是喊话时的满格。 */
const BAR_COUNT = 5;
const MIN_BAR_PX = 8;
const MAX_BAR_PX = 36;

/**
 * 简易音量动画：rAF 驱动，直接改 DOM 高度，避免 60fps 的 React 重渲染。
 * 老人看得见「系统真的在听」，不必靠猜。
 *
 * 尺寸全部可覆盖：小按钮里用默认的 5 根细条，录音大浮层里换成更多更粗的条。
 * 只改尺寸不改算法——两处的「声音越大条越高」必须完全一致，否则会像两套系统。
 */
export function LevelMeter({
  stream,
  active,
  bars = BAR_COUNT,
  minBarPx = MIN_BAR_PX,
  maxBarPx = MAX_BAR_PX,
  heightClass = 'h-9',
  barClass = 'w-2 rounded-full bg-red-500',
  className = '',
}: {
  stream: MediaStream | null;
  active: boolean;
  /** 条数。越多越像连续波形，但每根都要单独算相位，别给太大。 */
  bars?: number;
  minBarPx?: number;
  maxBarPx?: number;
  /** 容器高度档位。只用一个高度类，避免两个 h-* 同时生效、谁赢取决于样式表顺序。 */
  heightClass?: string;
  barClass?: string;
  className?: string;
}) {
  const barRefs = useRef<(HTMLDivElement | null)[]>([]);

  useEffect(() => {
    if (!active || !stream) return;
    const AudioCtor = window.AudioContext
      || (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
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
      // 时域数据的均方根就是这一帧的音量；乘 6 是为了让正常说话也能到明显的高度。
      const rms = Math.sqrt(sum / buffer.length);
      const peak = Math.max(0.08, Math.min(1, rms * 6));
      const now = Date.now();
      const total = barRefs.current.length;
      barRefs.current.forEach((bar, i) => {
        if (!bar) return;
        // 每根条错开一个相位：一起上下跳会像故障，错开才像人在说话
        const phase = (i / total) * Math.PI;
        const height = Math.max(minBarPx, peak * maxBarPx * (0.55 + 0.45 * Math.sin(now / 140 + phase)));
        bar.style.height = `${Math.min(maxBarPx, Math.round(height))}px`;
      });
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => { cancelAnimationFrame(frame); void context.close(); };
  }, [stream, active, minBarPx, maxBarPx]);

  return (
    <div className={`flex ${heightClass} items-end justify-center gap-1.5 ${className}`} aria-hidden="true">
      {Array.from({ length: bars }, (_, i) => (
        <div
          key={i}
          ref={el => { barRefs.current[i] = el; }}
          className={barClass}
          style={{ height: minBarPx }}
        />
      ))}
    </div>
  );
}
