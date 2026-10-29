/**
 * Copies the production bundle into the Spring Boot static resources.
 *
 * Run through `npm run build:backend`. Kept as a node script rather than a shell one
 * because the repo is developed on Windows and CI runs on Linux: `cp -r` and `xcopy`
 * are the two ways this step silently does the wrong thing.
 */
import { cp, mkdir, rm, stat } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDirectory = dirname(fileURLToPath(import.meta.url))
const frontendRoot = resolve(scriptDirectory, '..')
const source = join(frontendRoot, 'dist')
const target = resolve(frontendRoot, '..', 'src', 'main', 'resources', 'static', 'ui')

async function exists(path) {
  try {
    await stat(path)
    return true
  } catch {
    return false
  }
}

if (!(await exists(join(source, 'index.html')))) {
  console.error(`没有找到构建产物：${source}\n先运行 npm run build。`)
  process.exit(1)
}

// Removed first: a stale hashed asset from a previous build would otherwise stay
// around forever, and `index.html` would keep referencing nothing.
await rm(target, { recursive: true, force: true })
await mkdir(target, { recursive: true })
await cp(source, target, { recursive: true })

console.log(`已复制 ${source} → ${target}`)
console.log('后端会从 /ui/ 提供服务（含 /ui/** 的 SPA 回退，见 UiForwardController）。')
