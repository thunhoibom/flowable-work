import { ElMessageBox, type ElMessageBoxOptions } from 'element-plus'

export async function confirmAction(message: string, title: string, options?: ElMessageBoxOptions) {
  try {
    await ElMessageBox.confirm(message, title, options)
    return true
  } catch {
    return false
  }
}

export async function promptRequired(
  message: string,
  title: string,
  options?: ElMessageBoxOptions,
) {
  try {
    const result = await ElMessageBox.prompt(message, title, options)
    return result.value.trim() || null
  } catch {
    return null
  }
}
