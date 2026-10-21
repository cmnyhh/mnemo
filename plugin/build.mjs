// 把 src/ 打包成一个 ESM 文件：index.js。
//
// 宿主（dsh）提供 @deepseek-ai/cordis 与几个 dsh-* 包，所以它们保持 external——
// 打进产物会出现两份 cordis 上下文，插件就挂不到宿主上。
// @deepseek-ai/schemastery 反过来要打进来：它是插件自己的依赖，宿主那份不保证可用。
//
//   npm run build
import { build } from 'esbuild'

const host = [
  '@deepseek-ai/cordis',
  '@deepseek-ai/dsh-agent',
  '@deepseek-ai/dsh-llm',
  '@deepseek-ai/dsh-session',
  '@deepseek-ai/dsh-tools',
]

await build({
  entryPoints: ['src/index.ts'],
  outfile: 'index.js',
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: 'node20',
  external: host,
  legalComments: 'none',
  logLevel: 'info',
})
