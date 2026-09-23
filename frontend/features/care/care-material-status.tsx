'use client';

import { useEffect, useState } from 'react';
import { CalendarDays, Check, Circle, ImageIcon, LoaderCircle, RefreshCw } from 'lucide-react';
import { getAppointmentMaterials, getMaterialPhoto } from '@/lib/material-api';
import type { MaterialItem } from '@/types/domain';

/**
 * 这次复诊要带的材料，以及**准备到哪一步了**（照护者视角，只读）。
 *
 * 家属端原来这块只是预约里带出来的名字清单，谁也不知道就诊人准备到哪一步——
 * 而家属恰恰最需要知道这个：他人不在跟前，就是来远程确认「东西备齐没有」的。
 *
 * 状态只显示、不给勾选和拍照按钮：勾「已准备」是就诊人自己的动作（老人端材料清单里做），
 * 照护者替他勾等于替对方签字，出了门少东西算谁的说不清。
 */
export function CareMaterialStatus({ elderId, appointmentId, fallbackLabels, requiredMaterials }: {
  elderId: string;
  appointmentId: string;
  /** 状态读不出来时退回预约自带的名字清单：家属至少还知道要带什么，能照着催。 */
  fallbackLabels: string[];
  requiredMaterials: string[];
}) {
  /** null = 还没读到；[] = 真的一项都没有。两者在界面上必须说不同的话。 */
  const [materials, setMaterials] = useState<MaterialItem[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [viewing, setViewing] = useState<{ name: string; url: string } | null>(null);
  const [photoLoadingId, setPhotoLoadingId] = useState('');
  const [hint, setHint] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setMaterials(await getAppointmentMaterials(appointmentId, elderId));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '材料状态没有读到');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [appointmentId, elderId]);

  /** 看这一项拍过的照片。取回来才开浮层，避免先弹一个空白框干等。 */
  const openPhoto = async (item: MaterialItem) => {
    setHint('');
    setPhotoLoadingId(item.id);
    try {
      const url = await getMaterialPhoto(appointmentId, item.id, elderId);
      if (url) setViewing({ name: item.materialName, url });
      else setHint('这项材料的照片没有找到，可能被清掉了');
    } catch (cause) {
      setHint(cause instanceof Error ? cause.message : '照片没有读到，请重试');
    } finally {
      setPhotoLoadingId('');
    }
  };

  const done = materials?.filter(item => item.status !== 'NOT_PREPARED').length ?? 0;

  return (
    <section className="rounded-3xl border bg-card p-5 shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-lg font-bold"><CalendarDays className="size-5 text-primary" />需带材料</h2>
        {materials && materials.length > 0 && (
          <span className="shrink-0 rounded-full bg-secondary px-3 py-1 text-sm font-semibold">已准备 {done}/{materials.length}</span>
        )}
      </div>
      <p className="mt-1 text-base text-muted-foreground">提醒就诊人按此准备，无需本人到场</p>

      {loading && (
        <p className="mt-3 flex items-center gap-2 text-base text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />正在读取准备情况…</p>
      )}

      {/* 读失败必须说「没读到」：说成「没有材料要求」，家属就会以为真的不用带东西 */}
      {!loading && error && (
        <div className="mt-3 flex items-center justify-between gap-2 rounded-2xl bg-red-50 p-3 text-base text-red-700">
          <span>{error}</span>
          <button type="button" onClick={() => void load()} className="flex min-h-11 shrink-0 items-center gap-1 rounded-xl px-2 font-bold"><RefreshCw className="size-5" />重试</button>
        </div>
      )}

      {!loading && !error && materials && materials.length === 0 && (
        <p className="mt-3 text-base text-muted-foreground">这份预约没有记录到材料要求。</p>
      )}

      {!loading && !error && materials && materials.length > 0 && (
        <ul className="mt-3 divide-y">
          {materials.map(item => {
            const prepared = item.status !== 'NOT_PREPARED';
            return (
              <li key={item.id} className="flex min-h-14 items-center gap-3 py-2">
                <span className={`grid size-7 shrink-0 place-items-center rounded-full border-2 ${prepared ? 'border-primary bg-primary text-white' : 'border-muted-foreground/50'}`}>
                  {prepared ? <Check className="size-4" /> : <Circle className="size-3 opacity-0" />}
                </span>
                <span className="min-w-0 flex-1">
                  <span className={`text-base ${prepared ? 'text-muted-foreground line-through' : 'font-semibold'}`}>{item.materialName}</span>
                  {item.required && <span className="ml-2 text-sm text-primary">必带</span>}
                </span>
                <span className={`shrink-0 rounded-full px-2.5 py-0.5 text-sm font-bold ${prepared ? 'bg-green-100 text-green-800' : 'bg-amber-100 text-amber-800'}`}>
                  {item.status === 'PHOTO_CONFIRMED' ? '已拍照确认' : prepared ? '已准备' : '还没准备'}
                </span>
                {/* 拍过照的才给回看：家属隔着屏幕也认得出是不是那张卡 */}
                {item.status === 'PHOTO_CONFIRMED' && (
                  <button type="button" onClick={() => void openPhoto(item)} disabled={photoLoadingId === item.id} aria-label={`看${item.materialName}的照片`}
                    className="flex min-h-11 shrink-0 items-center gap-1 rounded-2xl border border-green-600 px-2 text-sm font-semibold text-green-700 disabled:opacity-40">
                    {photoLoadingId === item.id ? <LoaderCircle className="size-4 animate-spin" /> : <ImageIcon className="size-4" />}看照片
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {/* 状态没读到，至少把要带的名字给全 */}
      {!loading && error && fallbackLabels.length > 0 && (
        <div className="mt-3 rounded-2xl bg-muted/60 px-4 py-3">
          <p className="text-sm text-muted-foreground">这次要带的材料：</p>
          <ul className="mt-1 space-y-1">
            {fallbackLabels.map(label => (
              <li key={label} className="text-base">
                {label}{requiredMaterials.includes(label) && <span className="ml-2 text-sm text-primary">必带</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {hint && <p className="mt-2 text-sm text-[#8a6d3b]">{hint}</p>}
      <p className="mt-3 text-sm text-muted-foreground">准备情况由就诊人自己勾选，这里只能看。</p>

      {viewing && <CareMaterialPhotoViewer name={viewing.name} url={viewing.url} onClose={() => setViewing(null)} />}
    </section>
  );
}

/**
 * 看照片的浮层：占满屏幕、只有一个「关闭」按钮，不用找叉号。
 *
 * 老人端那份（materials/material-checklist.tsx 里的 MaterialPhotoViewer）没导出，
 * 为了共用去改那个文件不值得——两个端的这张浮层以后大概率会各长各的（比如这里要加「保存到手机」）。
 * 用原生 dialog（open）而不是 div+role：语义由浏览器给，屏幕阅读器认这个。
 */
function CareMaterialPhotoViewer({ name, url, onClose }: { name: string; url: string; onClose: () => void }) {
  return <dialog open aria-label={`${name}的照片`} className="fixed inset-0 z-50 grid place-items-center overflow-auto bg-black/85 p-4">
    <div className="w-full max-w-[440px] space-y-3">
      <p className="text-center text-base font-bold text-white">{name}·拍照确认</p>
      {/* 后端存的是 data URL，直接显示即可，不需要打包器的图片优化 */}
      {/* eslint-disable-next-line next/no-img-element */}
      <img src={url} alt={`${name}的照片`} className="max-h-[70dvh] w-full rounded-2xl bg-white object-contain" />
      <button type="button" onClick={onClose} className="flex min-h-12 w-full items-center justify-center rounded-2xl bg-white text-base font-bold text-[#6c3d24]">关闭</button>
    </div>
  </dialog>;
}
