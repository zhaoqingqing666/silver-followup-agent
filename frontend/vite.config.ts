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
  },
});
