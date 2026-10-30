import { readdir, stat } from 'node:fs/promises'
import { join } from 'node:path'

const assetDirectory = join(process.cwd(), 'dist', 'assets')
const budgets = {
  javascript: 420 * 1024,
  stylesheet: 450 * 1024,
}

const files = await readdir(assetDirectory)
const violations = []
for (const file of files) {
  const extension = file.endsWith('.js')
    ? 'javascript'
    : file.endsWith('.css')
      ? 'stylesheet'
      : null
  if (!extension) continue
  const bytes = (await stat(join(assetDirectory, file))).size
  if (bytes > budgets[extension]) {
    violations.push(
      `${file}: ${(bytes / 1024).toFixed(1)}KB > ${(budgets[extension] / 1024).toFixed(0)}KB`,
    )
  }
}

if (violations.length) {
  console.error('Bundle budget exceeded:')
  violations.forEach((violation) => console.error(`- ${violation}`))
  process.exit(1)
}

console.log('Bundle budget passed.')
