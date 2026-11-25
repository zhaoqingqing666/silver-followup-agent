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
 * “下次提醒：明天 08:00”。是今天/明天就直接说，老人算“9月23日离今天几天”很费劲。
 * 重复提醒的卡片文案（“每天 08:00”）顺延前后长得一模一样，只说这个才看得出点上了。
 */
function fmtNextRemind(iso: string): string {
  const [date, time] = iso.split('T');
  if (!date) return iso;
  const clock = time ? time.slice(0, 5) : '';
  const now = new Date();
  const day = (offset: number) => {
    const at = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset);
    return `${at.getFullYear()}-${pad2(at.getMonth() + 1)}-${pad2(at.getDate())}`;
  };
  if (date === day(0)) return `今天 ${clock}`;
  if (date === day(1)) return `明天 ${clock}`;
  const [, month, dayOfMonth] = date.split('-');
  return `${Number(month)}月${Number(dayOfMonth)}日 ${clock}`;
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
  /** 保存/删除/完成失败时的提示。以前这三处失败都当成功处理，老人以为改好了。 */
  const [error, setError] = useState('');
  /** 重复提醒顺延后的下一次，用来告诉老人“下次什么时候”；一次性提醒用不上。 */
  const [nextRemind, setNextRemind] = useState('');

  const failed = (cause: unknown, fallback: string) =>
      setError(cause instanceof Error ? cause.message : fallback);

  const todayInput = () => {
    const now = new Date();
    return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
  };

  // 修改：把原内容和提醒时间带进草稿，这一条就地变成编辑卡片。
  // **周期不带过来**，默认「仅一次」：和助手那边同一条规矩——周期只认明说的。
  // 原来把原周期也带进来，于是在这里只改个钟点、保存下去还是「每天」，
  // 而助手那边同一件事只提醒一次，两条路对不上。少了周期这件事下面会明写出来。
  const startEdit = () => {
    setEditing(true);
    setDraftText(memo.text);
    const timed = memo.remindAt != null;
    setRemindOn(timed);
    setDraftRepeat('');
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
    setError('');
    setNextRemind('');
    // 保存成功才收起编辑卡片：失败时留在编辑态、内容不丢，老人也不用重新输入一遍
    void updateMemo(memo.id, text, remindAt, repeat)
        .then(() => { setEditing(false); onChanged(); })
        .catch(cause => failed(cause, '修改没保存成功，请再试一次'));
  };

  /**
   * 「已完成」。重复提醒的点了不会结束，后端把它顺延到下一次，这里把新的到点说出来——
   * 卡片文案（“每天 08:00”）顺延前后一模一样，不说的话老人会以为没点着，再点一次就把
   * 明天的也推掉了。
   */
  const markDone = () => {
    setError('');
    void completeMemo(memo.id)
        .then(updated => {
          setNextRemind(updated.repeatRule && updated.remindAt ? fmtNextRemind(updated.remindAt) : '');
          onChanged();
        })
        .catch(cause => failed(cause, '没能标记完成，请再试一次'));
  };

  const removeMemo = () => {
    setConfirming(false);
    setError('');
    void deleteMemo(memo.id).then(onChanged).catch(cause => failed(cause, '没能删掉，请再试一次'));
  };

  const timed = memo.remindAt != null;
  /** 这条本来就重复、而这次选的不是重复：保存下去周期就没了，得在保存之前说出来。 */
  const droppingRepeat = remindOn && memo.remindAt !== null && memo.repeatRule !== null && !draftRepeat;

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
        {droppingRepeat && memo.remindAt && memo.repeatRule && (
          // 亮着的格子就是要保存的那个，但它和这条现在的样子不一样，光看格子看不出少了什么：
          // 每天吃药的那条从此只响一次，而他可能只是来改个字。明写出来，别让他保存完才发现。
          <p className="mt-2 rounded-xl border border-[#e0a866] bg-[#fff4e2] px-3 py-2 text-base font-bold text-[#7a4a24]">
            这条现在是{fmtRepeat(memo.remindAt, memo.repeatRule)}提醒；按这样保存，以后就只提醒这一次。要接着{REPEAT_OPTIONS.find(option => option.value === memo.repeatRule)?.label}提醒，点上面那一格。
          </p>
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
        {error && (
          <p role="alert" className="mt-3 rounded-xl bg-[#fdf0ed] px-3 py-2 text-base font-bold text-[#b3452f]">{error}</p>
        )}
        <div className="mt-3 flex gap-3">
          <button type="button" disabled={!draftText.trim()} onClick={saveEdit} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#1f7a4d] text-base font-bold text-white disabled:opacity-40">保存</button>
          <button type="button" onClick={() => { setEditing(false); setError(''); }} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-secondary text-base font-bold text-secondary-foreground">取消</button>
        </div>
      </li>
    );
  }

  return (
    <li className="rounded-3xl border bg-card p-4 shadow-sm">
      <p className="break-words text-[17px] leading-7">{memo.text}</p>
      <p className="mt-1 text-sm text-muted-foreground">{remindLabel(memo)}</p>
      {nextRemind && (
        <output className="mt-2 block rounded-xl bg-[#e7f3ec] px-3 py-2 text-base font-bold text-[#1f7a4d]">已记下，下次提醒：{nextRemind}</output>
      )}
      {error && (
        <p role="alert" className="mt-2 rounded-xl bg-[#fdf0ed] px-3 py-2 text-base font-bold text-[#b3452f]">{error}</p>
      )}
      {confirming ? (
        <div className="mt-3 flex items-center justify-between gap-3 rounded-2xl border border-[#e5b7ac] bg-[#fdf1ee] px-3 py-2">
          <span className="text-base font-bold text-[#7a2e1f]">删除这条备忘？</span>
          <span className="flex shrink-0 gap-2">
            <button type="button" onClick={removeMemo} className="min-h-11 rounded-xl bg-[#b3452f] px-4 text-base font-bold text-white">删除</button>
            <button type="button" onClick={() => setConfirming(false)} className="min-h-11 rounded-xl bg-secondary px-4 text-base font-bold text-secondary-foreground">取消</button>
          </span>
        </div>
      ) : (
        <div className="mt-3 flex gap-2 border-t pt-3">
          {timed && (
            // 重复提醒上写“已完成”会让人以为是“这条以后都完了”——它的意思只是“这次做完了”
            <button type="button" onClick={markDone} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#e7f3ec] text-base font-bold text-[#1f7a4d]">{memo.repeatRule ? '这次做完了' : '已完成'}</button>
          )}
          <button type="button" onClick={startEdit} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-secondary text-base font-bold text-secondary-foreground">修改</button>
          <button type="button" onClick={() => setConfirming(true)} className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#fdf0ed] text-base font-bold text-[#b3452f]">删除</button>
        </div>
      )}
    </li>
  );
}
