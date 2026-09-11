import tailwindcss from '@tailwindcss/postcss';
import vinext from 'vinext';
import { defineConfig } from 'vite';

export default defineConfig({
  css: { postcss: { plugins: [tailwindcss()] } },
  plugins: [vinext()],
  server: {
    // 容器内开发/真机联调：监听所有网卡，而不只是 localhost
    host: true,
    port: 3000,
    // WSL2 bind-mount 上冷编译很慢，首次访问极易超过 module-runner 的 60s
    // 传输超时（表现为首页 500 / "transport invoke timed out"）。
    // 启动阶段先把入口与 CSS 预编译进内存，冷启动成本转移到 dev server 起来之前。
    warmup: {
      clientFiles: ['app/layout.tsx', 'app/page.tsx', 'app/globals.css'],
      ssrFiles: ['app/layout.tsx', 'app/page.tsx', 'app/globals.css'],
    },
  },
});
