/** 后端地址；各 API 模块共用同一个兜底值。 */
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

/** 与后端 demo.user-id 保持一致；后端可用 DEMO_USER_ID 环境变量覆盖。 */
export const DEMO_USER_ID = process.env.NEXT_PUBLIC_DEMO_USER_ID ?? 'user-001';

/** 语音识别与语音合成共用的语言。 */
export const SPEECH_LANGUAGE = 'zh-CN';

/** 未连接后端、用户偏好尚未读取时的语音兜底参数。 */
export const DEFAULT_SPEECH_RATE = 0.9;
export const DEFAULT_SPEECH_VOLUME = 1;

/**
 * 快捷回答每页数量。
 * 后端 FollowupAgentService.QUICK_REPLY_PAGE_SIZE 用同一数值裁剪候选项，
 * 两边必须一致，否则确认性动作会被推到第二页。
 */
export const QUICK_REPLY_PAGE_SIZE = 3;

/** 人工帮助的演示提示语；接通真人客服前，所有入口共用同一句。 */
export const DEMO_HELP_MESSAGE = '这里是比赛演示的人工帮助入口，当前页面信息已经为您保留。';
