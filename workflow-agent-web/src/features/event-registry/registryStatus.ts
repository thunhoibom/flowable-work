/** Publish state of an event model or a channel, shown as a tag in the tables. */
export type RegistryStatus = 'unpublished' | 'changed' | 'published'

export const registryStatusLabel: Record<RegistryStatus, string> = {
  unpublished: 'Chưa phát hành',
  changed: 'Có thay đổi chưa phát hành',
  published: 'Đã phát hành',
}

export const registryStatusTag: Record<RegistryStatus, 'info' | 'warning' | 'success'> = {
  unpublished: 'info',
  changed: 'warning',
  published: 'success',
}

export function registryStatus(model: {
  publishedVersion?: number | null
  changed: boolean
}): RegistryStatus {
  if (!model.publishedVersion) return 'unpublished'
  return model.changed ? 'changed' : 'published'
}
