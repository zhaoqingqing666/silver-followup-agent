import type { Metadata } from 'next';

import './globals.css';

export const metadata: Metadata = {
  title: '银龄复诊助手',
  description: '面向银发群体的复诊事项协同办理智能体',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}

