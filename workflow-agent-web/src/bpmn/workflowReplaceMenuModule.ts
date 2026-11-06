import { callActivityEnabled } from '@/config/product'
import { filterReplaceEntries, type ReplaceTarget } from './subProcessModel'

interface PopupMenu {
  registerProvider(id: string, priority: number, provider: WorkflowReplaceMenuProvider): void
}

/**
 * Runs after the default bpmn-js replace menu and removes options the platform rejects,
 * such as transactions, ad-hoc subprocesses, and unsupported subprocess events.
 */
class WorkflowReplaceMenuProvider {
  static $inject = ['popupMenu']

  constructor(popupMenu: PopupMenu) {
    popupMenu.registerProvider('bpmn-replace', 500, this)
  }

  getPopupMenuEntries(target: ReplaceTarget) {
    return <T>(entries: Record<string, T>) =>
      filterReplaceEntries(target, entries, { callActivity: callActivityEnabled })
  }
}

export default {
  __init__: ['workflowReplaceMenuProvider'],
  workflowReplaceMenuProvider: ['type', WorkflowReplaceMenuProvider],
}
