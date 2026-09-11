/**
 * ASR / TTS API 客户端。
 * 调用后端 /api/asr 和 /api/tts，由后端转发给百炼 DashScope。
 */

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** 将 Blob 音频转为 base64 字符串（不含 data: 前缀） */
export function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result ?? '');
      const comma = result.indexOf(',');
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}

/** 语音识别：音频 Blob → 文字 */
export async function transcribeAudio(audioBlob: Blob, mimeType = 'audio/webm'): Promise<string | null> {
  try {
    const base64 = await blobToBase64(audioBlob);
    const response = await fetch(`${API_BASE}/api/asr/transcribe`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ audio: base64, mimeType }),
    });
    if (!response.ok) return null;
    const data = await response.json();
    return data.ok ? data.text : null;
  } catch {
    return null;
  }
}

/** TTS 结果内存缓存：同一段文本+音色+语速只合成一次，二次点击秒播 */
const ttsCache = new Map<string, string>();

/** 语音合成：文字 → 音频 URL（带缓存） */
export async function synthesizeSpeech(
  text: string,
  options?: { voice?: string; speed?: number },
): Promise<string | null> {
  if (!text.trim()) return null;
  const key = `${options?.voice ?? ''}|${options?.speed ?? ''}|${text}`;
  const cached = ttsCache.get(key);
  if (cached) return cached;
  try {
    const body: Record<string, unknown> = { text };
    if (options?.voice) body.voice = options.voice;
    if (options?.speed != null) body.speed = options.speed;
    const response = await fetch(`${API_BASE}/api/tts/synthesize`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!response.ok) return null;
    const data = await response.json();
    if (!data.ok) return null;
    if (ttsCache.size > 50) ttsCache.clear();
    ttsCache.set(key, data.audioUrl);
    return data.audioUrl;
  } catch {
    return null;
  }
}

/** 后台预合成（不播放）：收到 AI 回复时提前生成音频，用户点朗读时秒播 */
export function prewarmSpeech(text: string, options?: { voice?: string; speed?: number }): void {
  if (!text.trim()) return;
  void synthesizeSpeech(text, options);
}

/** 获取可用音色列表 */
export async function getVoices(): Promise<Array<{ id: string; label: string }>> {
  try {
    const r = await fetch(`${API_BASE}/api/tts/voices`);
    const data = await r.json();
    // 后端返回 { voices: [["Cherry", "芊悦 · 甜美小姐姐"], ...] }
    const raw = (data.voices || []) as Array<[string, string]>;
    return raw.map(([id, label]) => ({ id, label }));
  } catch {
    return [];
  }
}

/**
 * 单个语音服务的状态：
 *   ok          服务已连接，且后端说它是开的
 *   off         服务已连接，但后端明确说没开（功能关闭）
 *   unreachable 后端没连上 / 请求失败——这和"功能关闭"是两回事，不能混为一谈
 *   unknown     还没查过（初始态）
 */
export type VoiceServiceState = 'ok' | 'off' | 'unreachable' | 'unknown';

export interface VoiceStatus {
  /** 布尔字段沿用原有语义：只有确实可用才为 true */
  asr: boolean;
  tts: boolean;
  vl: boolean;
  /** 三个服务各自独立的状态，一个接口失败不会把另外两个判成 false */
  asrState: VoiceServiceState;
  ttsState: VoiceServiceState;
  vlState: VoiceServiceState;
}

/** 查询单个服务：网络失败要能和"后端明确说没开"区分开 */
async function checkService(path: string): Promise<VoiceServiceState> {
  try {
    const response = await fetch(`${API_BASE}${path}`);
    if (!response.ok) return 'unreachable';
    const data = await response.json();
    return data.enabled ? 'ok' : 'off';
  } catch {
    return 'unreachable';
  }
}

/** 查询 ASR/TTS/VL 是否可用。三个服务各自独立判断，互不连坐。 */
export async function getVoiceStatus(): Promise<VoiceStatus> {
  const [asrState, ttsState, vlState] = await Promise.all([
    checkService('/api/asr/status'),
    checkService('/api/tts/status'),
    checkService('/api/vl/status'),
  ]);
  return {
    asr: asrState === 'ok',
    tts: ttsState === 'ok',
    vl: vlState === 'ok',
    asrState, ttsState, vlState,
  };
}

/** 图片文件 → data URL (data:image/xxx;base64,...) */
export function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ''));
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

/** 视觉理解：图片 data URL + 提示词 → 模型回复文字 */
export async function analyzeImage(imageDataUrl: string, prompt = '帮我识别这张复诊材料是否齐全'): Promise<string | null> {
  if (!imageDataUrl) return null;
  try {
    const response = await fetch(`${API_BASE}/api/vl/analyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ imageDataUrl, prompt }),
    });
    if (!response.ok) return null;
    const data = await response.json();
    return data.ok ? data.result : null;
  } catch {
    return null;
  }
}
