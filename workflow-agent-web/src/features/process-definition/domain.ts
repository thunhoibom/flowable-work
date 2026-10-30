export interface ProcessIdentity {
  key: string
  name: string
}

const PROCESS_DEFINITION_KEY_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/

export function normalizeProcessIdentity(key: string, name: string): ProcessIdentity {
  const normalizedKey = key.trim()
  const normalizedName = name.trim()
  if (!normalizedKey || !normalizedName) {
    throw new Error('Mã quy trình và tên quy trình không được để trống')
  }
  if (!PROCESS_DEFINITION_KEY_PATTERN.test(normalizedKey)) {
    throw new Error(
      'Mã quy trình phải bắt đầu bằng chữ cái và chỉ gồm chữ cái, số, gạch dưới và gạch ngang',
    )
  }
  return { key: normalizedKey, name: normalizedName }
}
