'use client';

import { useState } from 'react';
import { completeMemo, deleteMemo, updateMemo } from '@/lib/memo-api';
import type { HealthMemo, MemoRepeat } from '@/types/domain';

const pad2 = (n: number) => String(n).padStart(2, '0');
const WEEKDAY_TEXT = '日一二三四五六';

/** 重复提醒的选项：空串=只提醒一次。 */
const REPEAT_OPTIONS: ReadonlyArray<{ value: MemoRepeat | ''; label: string }> = [
  { value: '', label: '仅一次' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
];

/** “2026-09-10T08:00:00” → “9月10日 08:00”；无时间为 null。 */
export function fmtRemind(iso: string | null): string | null {
  if (!iso) return null;
  const [date, time] = iso.split('T');
  if (!date) return iso;
  const [, month, day] = date.split('-');
  return `${Number(month)}月${Number(day)}日 ${time ? time.slice(0, 5) : ''}`;
}

/** 重复备忘的列表文案：“每天 08:00 / 每周三 15:00 / 每月15号 09:00”。 */
export function fmtRepeat(iso: string, repeatRule: MemoRepeat): string {
  const at = new Date(iso);
  const time = `${pad2(at.getHours())}:${pad2(at.getMinutes())}`;
  if (repeatRule === 'DAILY') return `每天 ${time}`;
  if (repeatRule === 'WEEKLY') return `每周${WEEKDAY_TEXT[at.getDay()]} ${time}`;
  return `每月${at.getDate()}号 ${time}`;
}

/** “提醒：每天 08:00”或“长期备忘”。 */
function remindLabel(memo: HealthMemo): string {
  if (!memo.remindAt) return '长期备忘';
  return `提醒：${memo.repeatRule ? fmtRepeat(memo.remindAt, memo.repeatRule) : fmtRemind(memo.remindAt)}`;
}

/**
 * 一条健康备忘的卡片：展示 + 修改 + 删除（到点提醒的还多一个“已完成”）。
 * 首页（有提醒时间的那些）和「我的记录」二级页（长期备忘）共用，两边行为一致。
 */
export function MemoCard({ memo, onChanged }: {
  memo: HealthMemo;
  /** 增删改落地后通知外面重新拉列表。 */
  onChanged: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [draftText, setDraftText] = useState('');
  const [remindOn, setRemindOn] = useState(false);
  const [draftDate, setDraftDate] = useState('');
  const [draftTime, setDraftTime] = useState('08:00');
  const [draftRepeat, setDraftRepeat] = useState<MemoRepeat | ''>('');

  const todayInput = () => {
    const now = new Date();
    return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
  };

  // 修改：把原内容/提醒时间带进草稿，这一条就地变成编辑卡片
  const startEdit = () => {
    setEditing(true);
    setDraftText(memo.text);
    const timed = memo.remindAt != null;
    setRemindOn(timed);
    setDraftRepeat(memo.repeatRule ?? '');
    if (timed && memo.remindAt) {
      const [date, time] = memo.remindAt.split('T');
      setDraftDate(date);
      setDraftTime(time ? time.slice(0, 5) : '08:00');
    } else {
      setDraftDate(todayInput());
      setDraftTime('08:00');
    }
  };

  const saveEdit = () => {
    const text = draftText.trim();
    if (!text) return;
    const remindAt = remindOn && draftDate ? `${draftDate}T${draftTime || '08:00'}:00` : null;
    // 选了“不提醒”就没有重复可言；重复规则只在有到点时间时生效
    const repeat = remindAt && draftRepeat ? draftRepeat : null;
    setEditing(false);
    void updateMemo(memo.id, text, remindAt, repeat).then(onChanged).catch(onChanged);
  };

  const timed = memo.remindAt != null;

  if (editing) {
    return (
      <li className="rounded-3xl border bg-card p-4 shadow-sm">
        <label htmlFor={`memo-text-${memo.id}`} className="block text-sm font-semibold text-muted-foreground">要记的事</label>
        <textarea id={`memo-text-${memo.id}`} value={draftText} onChange={event => setDraftText(event.target.value)} rows={2} className="mt-1 w-full rounded-2xl border bg-background px-3 py-2 text-[17px] leading-7" />
        <div className="mt-3">
          <p className="text-sm font-semibold text-muted-foreground">提醒方式</p>
          <div className="mt-1 flex gap-2">
            <button type="button" onClick={() => setRemindOn(true)} className={`flex min-h-11 flex-1 items-center justify-center rounded-xl px-2 text-base font-bold ${remindOn ? 'bg-primary text-white' : 'bg-secondary text-secondary-foreground'}`}>到点提醒</button>
            <button type="button" onClick={() => setRemindOn(false)} className={`flex min-h-11 flex-1 items-center justify-center rounded-xl px-2 text-base font-bold ${remindOn ? 'bg-secondary text-secondary-foreground' : 'bg-primary text-white'}`}>不提醒</button>
          </div>
        </div>
        {remindOn && (
          <div className="mt-3">
            <p className="text-sm font-semibold text-muted-foreground">重复提醒</p>
            <div className="mt-1 grid grid-cols-4 gap-2">
              {REPEAT_OPTIONS.map(option => (
                <button key={option.label} type="button" onClick={() => setDraftRepeat(option.value)} className={`flex min-h-11 items-center justify-center rounded-xl px-1 text-base font-bold ${draftRepeat === option.value ? 'bg-primary text-white' : 'bg-secondary text-secondary-foreground'}`}>{option.label}</button>
              ))}
            </div>
          </div>
        )}
        {remindOn && (
          <div className="mt-2 grid grid-cols-2 gap-2">
            <div className="rounded-xl border bg-background px-3 py-2">
              <span className="block text-sm font-semibold text-muted-foreground">提醒日期</span>
              <input type="date" value={draftDate} onChange={event => setDraftDate(event.target.value)} className="mt-0.5 w-full min-w-0 bg-transparent text-base" />
            </div>
            <div className="rounded-xl border bg-background px-3 py-2">
              <span className="block text-sm font-semibold text-muted-foreground">提醒时间</span>
              <input type="time" value={draftTime} onChange={event => { setDraftTime(event.target.value); event.currentTarget.blur(); }} className="mt-0.5 w-full min-w-0 bg-transparent text-base" />
            </div>
          </div>
        )}
        <div className="mt-3 flex gap-3">
          <button type="button" disabled={!draftText.trim()} onClick={saveEdit} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#1f7a4d] text-base font-bold text-white disabled:opacity-40">保存</button>
          <button type="button" onClick={() => setEditing(false)} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-secondary text-base font-bold text-secondary-foreground">取消</button>
        </div>
      </li>
    );
  }

  return (
    <li className="rounded-3xl border bg-card p-4 shadow-sm">
      <p className="break-words text-[17px] leading-7">{memo.text}</p>
      <p className="mt-1 text-sm text-muted-foreground">{remindLabel(memo)}</p>
      {confirming ? (
        <div className="mt-3 flex items-center justify-between gap-3 rounded-2xl border border-[#e5b7ac] bg-[#fdf1ee] px-3 py-2">
          <span className="text-base font-bold text-[#7a2e1f]">删除这条备忘？</span>
          <span className="flex shrink-0 gap-2">
            <button type="button" onClick={() => { setConfirming(false); void deleteMemo(memo.id).then(onChanged).catch(onChanged); }} className="min-h-11 rounded-xl bg-[#b3452f] px-4 text-base font-bold text-white">删除</button>
            <button type="button" onClick={() => setConfirming(false)} className="min-h-11 rounded-xl bg-secondary px-4 text-base font-bold text-secondary-foreground">取消</button>
          </span>
        </div>
      ) : (
        <div className="mt-3 flex gap-2 border-t pt-3">
          {timed && (
            <button type="button" onClick={() => { void completeMemo(memo.id).then(onChanged).catch(onChanged); }} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#e7f3ec] text-base font-bold text-[#1f7a4d]">已完成</button>
          )}
          <button type="button" onClick={startEdit} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-secondary text-base font-bold text-secondary-foreground">修改</button>
          <button type="button" onClick={() => setConfirming(true)} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#fdf0ed] text-base font-bold text-[#b3452f]">删除</button>
        </div>
      )}
    </li>
  );
}
