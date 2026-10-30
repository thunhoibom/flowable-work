import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'

const featuresRoot = join(process.cwd(), 'src', 'features')
const internalFeatureImport = /@\/features\/[^'"\s]+\/(?:api|types|components|views)(?:['"]|\/)/

async function sourceFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = await Promise.all(
    entries.map((entry) => {
      const path = join(directory, entry.name)
      return entry.isDirectory() ? sourceFiles(path) : [path]
    }),
  )
  return files.flat().filter((path) => /\.(ts|vue)$/.test(path))
}

describe('frontend architecture boundaries', () => {
  it('keeps cross-feature dependencies behind public feature entrypoints', async () => {
    const files = (await sourceFiles(featuresRoot)).filter((path) => !path.endsWith('index.ts'))
    const violations: string[] = []

    for (const file of files) {
      const content = await readFile(file, 'utf8')
      if (internalFeatureImport.test(content)) violations.push(file)
    }

    expect(violations.map((file) => relative(featuresRoot, file))).toEqual([])
  })

  it('requires a public entrypoint for every feature', async () => {
    const entries = await readdir(featuresRoot, { withFileTypes: true })
    const missing = []

    for (const entry of entries.filter((item) => item.isDirectory())) {
      const files = await readdir(join(featuresRoot, entry.name))
      if (files.some((file) => /^(api|types)\.ts$/.test(file)) && !files.includes('index.ts')) {
        missing.push(entry.name)
      }
    }

    expect(missing).toEqual([])
  })
})
