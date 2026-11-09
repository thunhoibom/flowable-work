const MESSAGE = 'Biến phải là một đối tượng JSON, ví dụ {"approved": true}'

/** Variables typed by the operator when continuing a wait step. Empty means none. */
export function parseWaitVariables(text: string): Record<string, unknown> {
  if (!text.trim()) return {}
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error(MESSAGE)
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(MESSAGE)
  return value as Record<string, unknown>
}
