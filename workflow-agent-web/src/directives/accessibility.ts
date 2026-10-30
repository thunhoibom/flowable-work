import { nextTick, type ObjectDirective } from 'vue'

function applyAccessibleLabel(element: HTMLElement, label: string) {
  const input = element.querySelector<HTMLInputElement>('.el-select__input')
  if (input) input.setAttribute('aria-label', label)
}

export const accessibleLabel: ObjectDirective<HTMLElement, string> = {
  mounted(element, binding) {
    void nextTick(() => applyAccessibleLabel(element, binding.value))
  },
  updated(element, binding) {
    if (binding.value !== binding.oldValue) {
      void nextTick(() => applyAccessibleLabel(element, binding.value))
    }
  },
}
