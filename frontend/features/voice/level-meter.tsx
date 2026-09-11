'use client';

import { useEffect, useRef } from 'react';

/**
 * 音量条的形状只有这一份：9 根 8px 粗、6px 间隔（合计 120px 宽），
 * 静止 14px、满格 72px，比录音浮层的容器（窄屏内宽 232px、高 80px）小一圈。
 *
 * 尺寸写成常量、不再做成入参，是踩过的坑：原先每个调用点各传一套，
 * 圆形按钮里那条 5 根 8–36px 塞进 56px 的内圆，只能靠 scale 硬缩，一按就压到白圈上。
 * 屏上只该有一条音量动画，形状也就只该有一处定义。
 */
const BAR_COUNT = 9;
const MIN_BAR_PX = 14;
const MAX_BAR_PX = 72;

/**
 * 音量动画：rAF 驱动，直接改 DOM 高度，避免 60fps 的 React 重渲染。
 * 老人看得见「系统真的在听」，不必靠猜。
 */
export function LevelMeter({ stream, active, barClass = 'w-2 rounded-full bg-red-500' }: {
  stream: MediaStream | null;
  active: boolean;
  /** 条的粗细与颜色。取消预备时换成琥珀色——这是唯一需要随状态变的东西。 */
  barClass?: string;
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
        const height = Math.max(MIN_BAR_PX, peak * MAX_BAR_PX * (0.55 + 0.45 * Math.sin(now / 140 + phase)));
        bar.style.height = `${Math.min(MAX_BAR_PX, Math.round(height))}px`;
      });
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => { cancelAnimationFrame(frame); void context.close(); };
  }, [stream, active]);

  return (
    <div className="flex h-20 items-end justify-center gap-1.5" aria-hidden="true">
      {Array.from({ length: BAR_COUNT }, (_, i) => (
        <div
          key={i}
          ref={el => { barRefs.current[i] = el; }}
          className={barClass}
          style={{ height: MIN_BAR_PX }}
        />
      ))}
    </div>
  );
}
