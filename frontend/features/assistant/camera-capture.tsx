'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Camera, LoaderCircle, RefreshCw, X } from 'lucide-react';
import { compressImageFile } from '@/lib/image-compress';

/** 尝试打开某一路摄像头；失败返回 null（供逐级回退） */
async function requestStream(facing?: 'environment' | 'user'): Promise<MediaStream | null> {
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: facing
        ? { facingMode: { ideal: facing }, width: { ideal: 1280 }, height: { ideal: 720 } }
        : true,
    });
  } catch {
    return null;
  }
}

/** 真实摄像头拍照：打开实时预览，点快门截图（JPEG data URL）交给上层。 */
export function CameraCapture({ onCapture, onClose }: { onCapture: (dataUrl: string) => void; onClose: () => void }) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [facing, setFacing] = useState<'environment' | 'user'>('environment');
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');

  const stopStream = useCallback(() => {
    streamRef.current?.getTracks().forEach(track => track.stop());
    streamRef.current = null;
  }, []);

  const openStream = useCallback(async (facingMode?: 'environment' | 'user') => {
    stopStream();
    setReady(false);
    setError('');
    // 后置 → 前置 → 任意摄像头，逐级回退
    const order: Array<'environment' | 'user' | undefined> =
      facingMode === 'environment' ? ['environment', 'user', undefined]
        : facingMode === 'user' ? ['user', undefined]
          : [undefined];
    for (const f of order) {
      const stream = await requestStream(f);
      if (stream) {
        streamRef.current = stream;
        const video = videoRef.current;
        if (video) { video.srcObject = stream; await video.play(); }
        setReady(true);
        return;
      }
    }
    setError('无法打开摄像头：请允许摄像头权限，或改用相册上传。');
  }, [stopStream]);

  useEffect(() => {
    void openStream('environment');
    return () => stopStream();
  }, [openStream, stopStream]);

  const capture = async () => {
    const video = videoRef.current;
    if (!video || !video.videoWidth || !video.videoHeight) return;
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext('2d')?.drawImage(video, 0, 0);
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.95));
    stopStream();
    if (!blob) { setError('拍照失败，请重试或改用相册上传。'); return; }
    // 走和相册上传同一条压缩链路：药盒照片自动走普通档，拍到说明书/检查单则走文字密集档，
    // 小字号不会被压糊，同时 1200 万像素的原图不会整张传上去。
    onCapture(await compressImageFile(new File([blob], 'camera.jpg', { type: 'image/jpeg' })));
  };

  const flip = () => {
    const next = facing === 'environment' ? 'user' : 'environment';
    setFacing(next);
    void openStream(next);
  };

  return (
    <div className="fixed inset-0 z-50 mx-auto flex max-w-[480px] flex-col bg-black">
      <div className="relative flex-1 overflow-hidden">
        <video ref={videoRef} playsInline muted className="h-full w-full object-cover" />
        {!ready && !error && (
          <div className="absolute inset-0 grid place-items-center"><LoaderCircle className="size-8 animate-spin text-white" /></div>
        )}
        {error && (
          <div className="absolute inset-0 grid place-items-center px-8 text-center text-base leading-7 text-white">{error}</div>
        )}
      </div>
      <div className="flex items-center justify-around bg-black px-6 py-7">
        <button type="button" onClick={onClose} aria-label="关闭相机" className="grid size-14 place-items-center rounded-full bg-white/10 text-white"><X className="size-7" /></button>
        <button type="button" onClick={() => void capture()} disabled={!ready} aria-label="拍照" className="grid size-20 place-items-center rounded-full border-4 border-white bg-white/25 text-white disabled:opacity-40"><Camera className="size-8" /></button>
        <button type="button" onClick={flip} aria-label="切换前后摄像头" className="grid size-14 place-items-center rounded-full bg-white/10 text-white"><RefreshCw className="size-6" /></button>
      </div>
    </div>
  );
}
