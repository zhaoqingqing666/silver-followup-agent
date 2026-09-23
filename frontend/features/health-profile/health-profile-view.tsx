'use client';

import { useEffect, useState } from 'react';
import { PageHeader } from '@/components/common/page-header';
import { DEMO_USER_ID } from '@/lib/app-config';
import { getUserProfile } from '@/lib/appointment-api';
import { HealthProfileCard } from './health-profile-card';

interface HealthProfileViewProps {
  onBack: () => void;
  /** 不传就是当前登录的老人本人。家属端要传长辈的 id。 */
  userId?: string;
  /** 谁在改。家属端由外面递进来（照护者自己的名字）；不传的话这一页自己去读。 */
  editorName?: string;
  /** 照护端各页都不带「人工帮助」，老人端带——跟两端其它页保持一致。 */
  hideHelp?: boolean;
}

/**
 * 健康档案整页。
 *
 * 入口做成一个只写「健康档案」的按钮，内容全收在这一页里——在信息页上铺开四段字
 * 会把真正要看的东西（地址、联系人）挤下去。点进去才展开。
 */
export function HealthProfileView({ onBack, userId = DEMO_USER_ID, editorName, hideHelp }: HealthProfileViewProps) {
  // 得先知道「我是谁」才能记下「最近是谁填的」；外面递了名字就直接用。
  const [name, setName] = useState(editorName ?? '');
  useEffect(() => {
    if (editorName) {
      setName(editorName);
      return;
    }
    let cancelled = false;
    getUserProfile(userId).then(
        user => { if (!cancelled) setName(user.name || '本人'); },
        () => { if (!cancelled) setName('本人'); });  // 读不到名字不该挡住填档案
    return () => { cancelled = true; };
  }, [editorName, userId]);

  return <main className="space-y-5 px-5 pb-8 pt-5">
    <PageHeader title="健康档案" onBack={onBack} hideHelp={hideHelp} />
    <HealthProfileCard editorName={name} userId={userId} />
  </main>;
}
