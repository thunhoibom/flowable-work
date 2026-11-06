import { describe, expect, it } from 'vitest'
import type { ModdleElement } from '@/bpmn/modeler-types'
import { enclosingSubProcessIds } from './diagramMarkers'

const process: ModdleElement = { $type: 'bpmn:Process', id: 'p' }
const outer: ModdleElement = { $type: 'bpmn:SubProcess', id: 'outer', $parent: process }
const inner: ModdleElement = { $type: 'bpmn:SubProcess', id: 'inner', $parent: outer }
const task: ModdleElement = { $type: 'bpmn:UserTask', id: 'task', $parent: inner }
const top: ModdleElement = { $type: 'bpmn:UserTask', id: 'top', $parent: process }

const lookup = (id: string) => ({ outer, inner, task, top })[id] as ModdleElement | undefined

describe('enclosingSubProcessIds', () => {
  it('returns every subprocess around the given activities, innermost first', () => {
    expect(enclosingSubProcessIds(['task'], lookup)).toEqual(['inner', 'outer'])
  })

  it('returns nothing for top-level or unknown activities', () => {
    expect(enclosingSubProcessIds(['top', 'missing'], lookup)).toEqual([])
  })

  it('does not repeat a subprocess shared by several activities', () => {
    expect(enclosingSubProcessIds(['task', 'inner'], lookup)).toEqual(['inner', 'outer'])
  })
})
