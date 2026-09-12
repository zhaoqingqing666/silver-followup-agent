/**
 * 上传前的图片压缩。
 *
 * 原图直传时 base64 还要再膨胀 33%，老人往往在上传阶段就干等很久。这里在浏览器端先缩放 + 转 JPEG。
 * 但不能一刀切压到 1280px——药品说明书、化验单上的小字号一旦压糊，OCR 就再也读不出来，
 * 省下的那点时间完全不值。所以分两档，按图片的"文字密度"自动选：
 *   - 文字密集（说明书、化验单、处方）→ 长边 2048px，保住小字号
 *   - 普通照片（药盒、就诊卡）        → 长边 1400px，够用且更快
 * 一律只缩不放：原图本来就比档位小的，保持原分辨率，绝不放大。
 */

import { fileToDataUrl } from './asr-tts-api';

const TEXT_TIER_LONG_EDGE = 2048;
const PHOTO_TIER_LONG_EDGE = 1400;
const JPEG_QUALITY = 0.85;

/** 文字密度检测只看一张很小的缩略图，几乎不额外耗时。 */
const DENSITY_SAMPLE = 96;
/** 相邻像素灰度差超过这个值算一条"边缘"（文字笔画）。 */
const EDGE_DELTA = 40;
/** 判定"文字密集"的边缘像素占比门槛。故意压低：宁可走大档慢一点，也不能让小字号糊掉。 */
const EDGE_DENSITY_THRESHOLD = 0.16;

export interface FittedSize {
  width: number;
  height: number;
  /** 实际缩放比，1 表示原图没超过档位、不需要缩。 */
  scale: number;
}

/** 按长边上限等比计算目标尺寸；只缩不放。 */
export function fitWithin(width: number, height: number, maxLongEdge: number): FittedSize {
  const longEdge = Math.max(width, height);
  const scale = longEdge > maxLongEdge ? maxLongEdge / longEdge : 1;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
    scale,
  };
}

/**
 * 判断图片是不是"文字密集"：缩到小图后统计灰度突变像素的占比。
 * 文字笔画会在小尺寸下产生大量边缘，照片则少得多。
 * 测不出来时按文字密集处理——偏向保真。
 */
function isTextDense(bitmap: ImageBitmap): boolean {
  const { width, height } = fitWithin(bitmap.width, bitmap.height, DENSITY_SAMPLE);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return true;
  ctx.drawImage(bitmap, 0, 0, width, height);

  const { data } = ctx.getImageData(0, 0, width, height);
  const gray = new Float32Array(width * height);
  for (let i = 0; i < gray.length; i++) {
    const p = i * 4;
    gray[i] = 0.299 * data[p] + 0.587 * data[p + 1] + 0.114 * data[p + 2];
  }

  let edges = 0;
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const i = y * width + x;
      const dx = Math.abs(gray[i + 1] - gray[i - 1]);
      const dy = Math.abs(gray[i + width] - gray[i - width]);
      if (dx + dy > EDGE_DELTA) edges++;
    }
  }
  return edges / (width * height) >= EDGE_DENSITY_THRESHOLD;
}

/** 画到画布并编码为 JPEG data URL。先铺白底：截图类 PNG 有透明通道，直接转 JPEG 会变黑。 */
function renderToJpeg(bitmap: ImageBitmap, maxLongEdge: number): string {
  const { width, height } = fitWithin(bitmap.width, bitmap.height, maxLongEdge);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('canvas unavailable');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, width, height);
  ctx.drawImage(bitmap, 0, 0, width, height);
  const dataUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY);
  if (!dataUrl.startsWith('data:image/jpeg')) throw new Error('unexpected encoding');
  return dataUrl;
}

/**
 * 压缩一张图片文件，返回 data URL。
 * 任何一步失败（老浏览器没有 createImageBitmap、canvas 不可用等）都退回原图上传——
 * 绝不因为压缩失败让老人传不了图。
 */
export async function compressImageFile(file: File): Promise<string> {
  try {
    const bitmap = await createImageBitmap(file);
    try {
      const maxLongEdge = isTextDense(bitmap) ? TEXT_TIER_LONG_EDGE : PHOTO_TIER_LONG_EDGE;
      return renderToJpeg(bitmap, maxLongEdge);
    } finally {
      bitmap.close();
    }
  } catch {
    return fileToDataUrl(file);
  }
}
