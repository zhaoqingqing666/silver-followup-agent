'use client';

import { useEffect, useState } from 'react';
import { LoaderCircle, Pencil, RefreshCw, ShieldCheck } from 'lucide-react';
import { getHealthProfile, saveHealthProfile } from '@/lib/health-profile-api';
import { getHealthRecords } from '@/lib/health-record-api';
import type { HealthProfile } from '@/types/domain';

interface HealthProfileCardProps {
  /** 谁在改：显示「最近是谁填的」，保存时一起写进去。 */
  editorName: string;
  /** 不传就是当前登录的老人本人；家属端要传长辈的 id。 */
  userId?: string;
}

/** 2026-09-10T11:20:00 → 9月10日；2026-09-13T21:57:15.9 → 9月13日 21:57 */
const fmtDay = (value: string) => {
  const [, month, day] = value.slice(0, 10).split('-');
  return `${Number(month)}月${Number(day)}日`;
};
const fmtMoment = (value: string) =>
    `${fmtDay(value)} ${value.slice(11, 16)}`;

/**
 * 健康档案：过敏史、既往病史、身高、体重。
 *
 * 两端用的是同一个组件、同一个接口、同一份数据，所以家属填完老人端就是新的，
 * 反过来也一样，不需要任何同步。
 *
 * 身高体重旁边会带一行「最近一次记录」：档案里是「现在是多少」，健康记录里是
 * 「每次量的历史」，两者可能不一致，摆在一起才不会互相打架还看不出来。
 *
 * 助手看不到这块内容（提示词里没有、也没有查询工具），所以它不会拿这些给建议——
 * 页脚那句就是把这件事告诉用户，免得他们以为助手知道。
 */
export function HealthProfileCard({ editorName, userId }: HealthProfileCardProps) {
  const [profile, setProfile] = useState<HealthProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  /** 最近一次健康记录里的身高/体重，用来跟档案里的当前值对照。读不到就不显示，不挡着主功能。 */
  const [latest, setLatest] = useState<{ height: string; weight: string }>({ height: '', weight: '' });
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState({ heightCm: '', weightKg: '', allergies: '', medicalHistory: '' });
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const row = await getHealthProfile(userId);
      setProfile(row);
      setDraft({
        heightCm: row.heightCm === null ? '' : String(row.heightCm),
        weightKg: row.weightKg === null ? '' : String(row.weightKg),
        allergies: row.allergies ?? '',
        medicalHistory: row.medicalHistory ?? '',
      });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法读取健康档案');
    } finally {
      setLoading(false);
    }
    // 对照行是锦上添花：健康记录读失败就空着，不能因此把档案本身也显示成坏的
    const [height, weight] = await Promise.all([
      getHealthRecords(1, 0, '身高', userId).catch(() => []),
      getHealthRecords(1, 0, '体重', userId).catch(() => []),
    ]);
    setLatest({
      height: height[0] ? `${height[0].valueText} ${height[0].unit}（${fmtDay(height[0].recordedAt)}）` : '',
      weight: weight[0] ? `${weight[0].valueText} ${weight[0].unit}（${fmtDay(weight[0].recordedAt)}）` : '',
    });
  };

  useEffect(() => { void load(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [userId]);

  const save = async () => {
    setSaving(true);
    setSaveError('');
    try {
      // 四项一起提交，空串表示「清掉」——两个人先后各改一项不会互相抹掉没动的那项
      setProfile(await saveHealthProfile({ ...draft, editorName }, userId));
      setEditing(false);
    } catch (cause) {
      // 后端那句原话（「身高请在50到250厘米之间」）比「保存失败」有用得多
      setSaveError(cause instanceof Error ? cause.message : '健康档案没有保存成功');
    } finally {
      setSaving(false);
    }
  };

  const value = (text: string | null | undefined) => text?.trim() || '未填写';

  if (loading) {
    return <section className="grid min-h-32 place-items-center rounded-3xl border bg-card shadow-sm">
      <div className="text-center text-muted-foreground"><LoaderCircle className="mx-auto size-7 animate-spin" /><p className="mt-2 text-base">正在读取健康档案…</p></div>
    </section>;
  }

  if (error || !profile) {
    return <section className="rounded-3xl border bg-card p-6 text-center">
      <p className="text-base">{error || '无法读取健康档案'}</p>
      <button onClick={() => void load()} className="mt-4 inline-flex min-h-12 items-center gap-2 rounded-2xl bg-primary px-5 font-bold text-white"><RefreshCw className="size-5" />重新读取</button>
    </section>;
  }

  return <section className="rounded-3xl border bg-card p-5 shadow-sm">
    <div className="flex items-center gap-2">
      {/* 这块内容只在 HealthProfileView 那一页里出现，页头已经写了「健康档案」，
          这里不再重复一遍标题，免得同一屏上出现两个一样的字。 */}
      {!editing && <button type="button" onClick={() => { setDraft({
        heightCm: profile.heightCm === null ? '' : String(profile.heightCm),
        weightKg: profile.weightKg === null ? '' : String(profile.weightKg),
        allergies: profile.allergies ?? '',
        medicalHistory: profile.medicalHistory ?? '',
      }); setSaveError(''); setEditing(true); }}
        className="ml-auto inline-flex min-h-11 items-center gap-2 rounded-2xl border px-4 text-base font-bold">
        <Pencil className="size-4" />填写/修改
      </button>}
    </div>

    {editing ? (
      <div className="mt-3 grid gap-3">
        <div className="grid grid-cols-2 gap-3">
          <label className="block rounded-xl border bg-background px-3 py-2">
            <span className="block text-sm font-semibold text-muted-foreground">身高（厘米）</span>
            <input type="number" inputMode="decimal" value={draft.heightCm}
              onChange={event => setDraft({ ...draft, heightCm: event.target.value })}
              className="mt-0.5 w-full min-w-0 bg-transparent text-lg" placeholder="例如 158" />
          </label>
          <label className="block rounded-xl border bg-background px-3 py-2">
            <span className="block text-sm font-semibold text-muted-foreground">体重（公斤）</span>
            <input type="number" inputMode="decimal" value={draft.weightKg}
              onChange={event => setDraft({ ...draft, weightKg: event.target.value })}
              className="mt-0.5 w-full min-w-0 bg-transparent text-lg" placeholder="例如 61.5" />
          </label>
        </div>
        <label className="block rounded-xl border bg-background px-3 py-2">
          <span className="block text-sm font-semibold text-muted-foreground">过敏史（药物 / 食物）</span>
          <textarea rows={2} value={draft.allergies}
            onChange={event => setDraft({ ...draft, allergies: event.target.value })}
            className="mt-0.5 w-full min-w-0 bg-transparent text-base leading-7" placeholder="例如 青霉素过敏；吃海鲜会起疹子" />
        </label>
        <label className="block rounded-xl border bg-background px-3 py-2">
          <span className="block text-sm font-semibold text-muted-foreground">既往病史（确诊的慢性病）</span>
          <textarea rows={2} value={draft.medicalHistory}
            onChange={event => setDraft({ ...draft, medicalHistory: event.target.value })}
            className="mt-0.5 w-full min-w-0 bg-transparent text-base leading-7" placeholder="例如 高血压（2019年确诊）；2型糖尿病" />
        </label>
        {/* 医生问起时最要紧的就是这两行，写不下就精简，不能靠截断悄悄少掉几个字 */}
        <p className="text-sm leading-6 text-muted-foreground">不用填得很全，写清「对什么过敏」「确诊过什么」就够，具体的以医生说的为准。留空表示这一项没有。</p>
        {saveError && <p className="text-base font-bold text-red-700">{saveError}</p>}
        <div className="flex gap-3">
          <button type="button" onClick={() => void save()} disabled={saving}
            className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-[#1f7a4d] text-base font-bold text-white disabled:opacity-50">
            {saving ? '正在保存…' : '保存'}
          </button>
          <button type="button" onClick={() => setEditing(false)} disabled={saving}
            className="flex min-h-12 flex-1 items-center justify-center rounded-2xl bg-secondary text-base font-bold text-secondary-foreground disabled:opacity-50">取消</button>
        </div>
      </div>
    ) : (
      <>
        <div className="mt-3 grid gap-3 text-base">
          <div>
            <p className="flex items-center gap-2"><span className="text-muted-foreground">身高</span><span className="ml-auto font-bold">{profile.heightCm === null ? '未填写' : `${Number(profile.heightCm)} 厘米`}</span></p>
            {latest.height && <p className="mt-0.5 text-right text-sm text-muted-foreground">最近一次记录：{latest.height}</p>}
          </div>
          <div>
            <p className="flex items-center gap-2"><span className="text-muted-foreground">体重</span><span className="ml-auto font-bold">{profile.weightKg === null ? '未填写' : `${Number(profile.weightKg)} 公斤`}</span></p>
            {latest.weight && <p className="mt-0.5 text-right text-sm text-muted-foreground">最近一次记录：{latest.weight}</p>}
          </div>
          <div className="border-t pt-3">
            <p className="text-muted-foreground">过敏史</p>
            <p className="mt-1 text-lg font-bold leading-8">{value(profile.allergies)}</p>
          </div>
          <div>
            <p className="text-muted-foreground">既往病史</p>
            <p className="mt-1 text-lg font-bold leading-8">{value(profile.medicalHistory)}</p>
          </div>
        </div>

        <div className="mt-4 flex items-start gap-2 border-t pt-3 text-sm leading-6 text-muted-foreground">
          <ShieldCheck className="mt-0.5 size-4 shrink-0 text-primary" />
          <p>
            {/* 判断「有没有人填过」看的是 updatedBy：种子数据的 updatedAt 是空的（见后端 HealthProfileStore），
                那时候只说「由谁填写」，不编一个钟点出来。 */}
            {profile.updatedBy
              ? `最近由${profile.updatedBy}${profile.updatedAt ? `在${fmtMoment(profile.updatedAt)}` : ''}填写。`
              : '还没有人填写过。'}
            助手看不到这块内容，也不会拿它给建议；填错了随时可以改。
          </p>
        </div>
      </>
    )}
  </section>;
}
