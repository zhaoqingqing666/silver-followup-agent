import type { ReactNode } from 'react';

/**
 * 全屏遮罩 + 内容居中的浮层（确认弹窗、看照片都用它）。
 *
 * 为什么用原生 <dialog open> 而不是 div + role="dialog"：语义由浏览器给，屏幕阅读器认。
 *
 * 但原生 dialog 有两处默认样式会打架，必须在这里一次压住，否则弹窗会缩在**左上角**、
 * 遮罩也只盖住弹窗那么大一块：
 *   1. 浏览器 UA 样式给 dialog 定的是 `width/height: fit-content` + `margin: auto`；
 *   2. Tailwind preflight 又把 `*` 的 margin 归零。
 * 于是只写 `fixed inset-0` 就成了「四边都钉 0、但宽高是收缩值」的过约束盒子——
 * 按 CSS 过约束规则 left/top 胜出、right/bottom 被忽略，盒子贴在左上角。
 * 所以这里显式补 `h-full w-full`：宽高变成视口尺寸，四边钉 0 才真的铺满全屏。
 *
 * 内容用 `flex + min-h-full + items-center` 居中，而不是 `place-items-center`：
 * 弹窗比屏幕高时（大字模式、横屏），前者还能往上滚看到，后者会把内容顶出可视区、滚不到。
 */
export function PlainDialog({ label, backdropClassName = 'bg-black/50', children }: {
  label: string;
  /** 遮罩深浅：普通确认用默认的 bg-black/50，看照片要更实，传 bg-black/85。 */
  backdropClassName?: string;
  children: ReactNode;
}) {
  return <dialog open aria-label={label}
    className={`fixed inset-0 z-50 h-full w-full overflow-y-auto overscroll-contain ${backdropClassName}`}>
    <div className="flex min-h-full items-center justify-center p-4">
      {children}
    </div>
  </dialog>;
}
