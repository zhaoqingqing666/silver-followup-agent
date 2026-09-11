'use client';

/**
 * 页面内的只读语音命令：返回、重听、切换院内/院外。
 * 只处理当前页面自己的事；除此之外的话都交给助手，由后端确定性通道解析。
 */
export type PageVoiceCommand = 'BACK' | 'REPEAT' | 'INSIDE' | 'OUTSIDE';

/**
 * 只要出现这些内容就一律交给后端。
 * “我胸口疼，帮我打开地图”里也有页面口令，但这类表达必须先过后端的 SafetyGuard，
 * 绝不能在前端就地跳页把安全判定绕过去。
 */
const DEFER_TO_AGENT = [
  '疼', '痛', '胸闷', '胸痛', '心慌', '不舒服', '难受', '头晕', '恶心',
  '喘不上气', '呼吸困难', '出血', '昏迷', '急救', '120',
];

/** 超过这个长度说明用户是在说一整句话，而不是页面口令。 */
const COMMAND_LIMIT = 12;

const COMMANDS: Array<{ command: PageVoiceCommand; words: string[] }> = [
  {
    command: 'INSIDE',
    words: [
      '院内指引', '院内怎么走', '院内路线', '院里的路线', '楼里怎么走', '到院后怎么走',
      '医院里面', '到医院里面', '进去以后', '进医院以后', '楼里怎么走', '在哪个房间',
    ],
  },
  { command: 'OUTSIDE', words: ['院外路线', '外面的路线', '去医院的路线', '怎么去医院'] },
  { command: 'REPEAT', words: ['再念一遍', '再读一遍', '重复一遍', '再说一遍', '重新念', '再念一次', '再听一遍'] },
  { command: 'BACK', words: ['返回', '退出', '回去'] },
];

export function matchPageVoiceCommand(text: string): PageVoiceCommand | null {
  const value = text.trim();
  if (!value) return null;
  // 只认短口令：长句子里出现“医院里面”往往另有语境，交给后端理解更安全。
  if (value.length > COMMAND_LIMIT) return null;
  // “返回修改”是确认卡上的动作，不是页面返回。
  if (value.includes('返回修改')) return null;
  if (DEFER_TO_AGENT.some(word => value.includes(word))) return null;
  for (const item of COMMANDS) {
    if (!item.words.some(word => value.includes(word))) continue;
    // “返回/回去”太容易出现在别的句子里（“我什么时候回去复诊”），只认短口令。
    if (item.command === 'BACK' && value.length > 6) continue;
    return item.command;
  }
  return null;
}
