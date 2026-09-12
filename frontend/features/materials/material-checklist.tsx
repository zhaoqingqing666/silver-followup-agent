'use client';

import { useRef, useState, type ChangeEvent } from 'react';
import { Camera, Check, Circle, Image as ImageIcon, ImagePlus, LoaderCircle, RefreshCw } from 'lucide-react';
import { useAppointmentMaterials } from '@/hooks/use-appointment-materials';
import { compressImageFile } from '@/lib/image-compress';
import type { MaterialItem } from '@/types/domain';
import { CameraCapture } from '@/features/assistant/camera-capture';

/** 看照片的浮层：老人自己拍的那张，占满屏幕、只有一个「关闭」按钮，不用找叉号。
 *  用原生 dialog（open）而不是 div+role：语义由浏览器给，屏幕阅读器认这个。 */
function MaterialPhotoViewer({ name, url, onClose }: { name: string; url: string; onClose: () => void }) {
  return <dialog open aria-label={`${name}的照片`} className="fixed inset-0 z-50 grid place-items-center overflow-auto bg-black/85 p-4">
    <div className="w-full max-w-[440px] space-y-3">
      <p className="text-center text-base font-bold text-white">{name}·拍照确认</p>
      {/* 老人当场拍的那张，data URL 直接显示即可，不需要打包器的图片优化 */}
      {/* eslint-disable-next-line next/no-img-element */}
      <img src={url} alt={`${name}的照片`} className="max-h-[70dvh] w-full rounded-2xl bg-white object-contain" />
      <button type="button" onClick={onClose} className="flex min-h-12 w-full items-center justify-center rounded-2xl bg-white text-base font-bold text-[#6c3d24]">关闭</button>
    </div>
  </dialog>;
}

export function MaterialChecklist({ appointmentId, disabled = false, compact = false }: {
  appointmentId: string;
  disabled?: boolean;
  compact?: boolean;
}) {
  const { materials, loading, updatingId, error, reload, toggle, confirmWithPhoto, loadPhoto } = useAppointmentMaterials(appointmentId);
  const done = materials.filter(item => item.status !== 'NOT_PREPARED').length;
  /** 这一行正在等哪张照片：拍照走浮层，相册走隐藏的 file input，两条路最后都回到 confirmWithPhoto。 */
  const photoTarget = useRef<MaterialItem | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [cameraOpen, setCameraOpen] = useState(false);
  /** 正在看的那张照片；photoLoadingId 用来给按下「看照片」的那一行转圈 */
  const [viewing, setViewing] = useState<{ name: string; url: string } | null>(null);
  const [photoLoadingId, setPhotoLoadingId] = useState('');
  const [hint, setHint] = useState('');

  const takePhoto = (item: MaterialItem) => {
    photoTarget.current = item;
    setCameraOpen(true);
  };

  /** 相册：没有摄像头（台式机、非 HTTPS）时唯一的出路，所以和拍照并排放。 */
  const pickPhoto = (item: MaterialItem) => {
    photoTarget.current = item;
    fileInput.current?.click();
  };

  /** 看这一行已经拍过的照片。取回来才开浮层，避免先弹一个空白框干等。 */
  const openPhoto = async (item: MaterialItem) => {
    setHint('');
    setPhotoLoadingId(item.id);
    try {
      const url = await loadPhoto(item);
      if (url) setViewing({ name: item.materialName, url });
      else setHint('这项材料的照片没有找到，可以重新拍一张');
    } finally {
      setPhotoLoadingId('');
    }
  };

  const applyPhoto = async (dataUrl: string) => {
    const target = photoTarget.current;
    photoTarget.current = null;
    setCameraOpen(false);
    if (target) await confirmWithPhoto(target, dataUrl);
  };

  const onPicked = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    // 先清 value：同一张图连选两次也要能再触发 change
    event.target.value = '';
    if (!file) return;
    await applyPhoto(await compressImageFile(file));
  };

  if (loading) return <div className="flex items-center gap-2 py-4 text-muted-foreground"><LoaderCircle className="size-5 animate-spin" />正在读取材料状态…</div>;

  return <section className={compact ? 'mt-4 rounded-2xl bg-white/70 p-4' : 'rounded-3xl border bg-card p-5 shadow-sm'}>
    <div className="flex items-center justify-between gap-3">
      <h2 className={compact ? 'font-bold' : 'text-xl font-bold'}>材料清单</h2>
      <span className="rounded-full bg-secondary px-3 py-1 text-sm font-semibold">已准备 {done}/{materials.length}</span>
    </div>
    {!compact && <p className="mt-1 text-base text-muted-foreground">点一下会保存到数据库，刷新后仍会保留；也可以拍张照片确认</p>}
    {error && <div className="mt-3 flex items-center justify-between gap-2 rounded-2xl bg-red-50 p-3 text-sm text-red-700"><span>{error}</span><button onClick={() => void reload()} aria-label="重新读取材料"><RefreshCw className="size-5" /></button></div>}
    <div className="mt-3 divide-y">
      {materials.map(item => {
        const prepared = item.status !== 'NOT_PREPARED';
        const updating = updatingId === item.id;
        const busy = disabled || !!updatingId;
        return <div key={item.id} className="flex items-center gap-2">
          <button type="button" disabled={busy} onClick={() => void toggle(item)} className="flex min-h-14 min-w-0 flex-1 items-center gap-3 py-3 text-left disabled:opacity-60">
            <span className={`grid size-7 shrink-0 place-items-center rounded-full border-2 ${prepared ? 'border-primary bg-primary text-white' : 'border-muted-foreground/50'}`}>
              {updating ? <LoaderCircle className="size-4 animate-spin" /> : prepared ? <Check className="size-4" /> : <Circle className="size-3 opacity-0" />}
            </span>
            <span className={`${compact ? 'text-base' : 'text-lg'} ${prepared ? 'text-muted-foreground line-through' : 'font-semibold'}`}>{item.materialName}</span>
            {item.required && item.status !== 'PHOTO_CONFIRMED' && <span className="ml-auto shrink-0 text-sm text-primary">必带</span>}
          </button>
          {/* 拍过照的行给一个回看入口：确认状态看得见，照片本身也要看得见 */}
          {item.status === 'PHOTO_CONFIRMED' && <button type="button" onClick={() => void openPhoto(item)} disabled={busy || photoLoadingId === item.id} aria-label={`看${item.materialName}的照片`} className="flex min-h-11 shrink-0 items-center gap-1 rounded-2xl border border-green-600 px-2 text-sm font-semibold text-green-700 disabled:opacity-40">
            {photoLoadingId === item.id ? <LoaderCircle className="size-4 animate-spin" /> : <ImageIcon className="size-4" />}看照片
          </button>}
          {/* 拍照 / 相册各一个按钮：老人手上是手机就拍，是电脑就用相册里现成的照片 */}
          <button type="button" disabled={busy} onClick={() => takePhoto(item)} aria-label={`拍照确认${item.materialName}`} className={`grid size-11 shrink-0 place-items-center rounded-2xl border ${item.status === 'PHOTO_CONFIRMED' ? 'border-green-600 text-green-700' : 'border-[#dfb98f] text-primary'} disabled:opacity-40`}><Camera className="size-5" /></button>
          <button type="button" disabled={busy} onClick={() => pickPhoto(item)} aria-label={`从相册选${item.materialName}的照片`} className="grid size-11 shrink-0 place-items-center rounded-2xl border border-[#dfb98f] text-primary disabled:opacity-40"><ImagePlus className="size-5" /></button>
        </div>;
      })}
      {!materials.length && !error && <p className="py-4 text-muted-foreground">暂无材料记录</p>}
    </div>
    {hint && <p className="mt-2 text-sm text-[#8a6d3b]">{hint}</p>}
    <input ref={fileInput} type="file" accept="image/*" className="hidden" onChange={event => void onPicked(event)} />
    {cameraOpen && <CameraCapture onCapture={dataUrl => void applyPhoto(dataUrl)} onClose={() => { photoTarget.current = null; setCameraOpen(false); }} />}
    {viewing && <MaterialPhotoViewer name={viewing.name} url={viewing.url} onClose={() => setViewing(null)} />}
  </section>;
}
