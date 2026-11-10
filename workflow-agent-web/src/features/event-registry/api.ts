import { apiClient } from '@/api/http'
import type { PageResult } from '@/types/api'

export type EventFieldType = 'string' | 'integer' | 'long' | 'double' | 'boolean' | 'json'

export interface EventFieldView {
  name: string
  type: EventFieldType
  correlation: boolean
  header: boolean
  fullPayload: boolean
}

export interface EventModelView {
  key: string
  name: string
  description?: string | null
  fields: EventFieldView[]
  revision: number
  updatedBy: string
  updatedAt: string
  publishedVersion?: number | null
  publishedAt?: string | null
  publishedBy?: string | null
  publishedFields?: EventFieldView[] | null
  changed: boolean
}

export interface SaveEventModelCommand {
  revision: number
  name: string
  description?: string
  fields: EventFieldView[]
}

export type KeyDetection = 'FIXED' | 'JSON_FIELD' | 'JSON_POINTER'

export interface RestChannelView {
  key: string
  name: string
  description?: string | null
  detection: KeyDetection
  detectionValue: string
  allowedEventKeys: string[]
  userIds: string[]
  groupIds: string[]
  revision: number
  updatedBy: string
  updatedAt: string
  publishedVersion?: number | null
  publishedAt?: string | null
  publishedBy?: string | null
  changed: boolean
  webhookPath: string
}

export interface SaveRestChannelCommand {
  revision: number
  name: string
  description?: string
  detection: KeyDetection
  detectionValue: string
  allowedEventKeys: string[]
  userIds: string[]
  groupIds: string[]
}

export interface EventWaitView {
  processInstanceId: string
  processDefinitionKey?: string | null
  processDefinitionName?: string | null
  businessKey?: string | null
  activityId: string
  activityName?: string | null
  executionId: string
  since?: string | null
}

export interface EventUsageView {
  processDefinitionKey: string
  version: number
  activityIds: string[]
}

export interface EventUsagesView {
  usages: EventUsageView[]
  waiting: number
}

const EVENTS = '/workflow/management/event-registry/events'
const WAITS = '/workflow/management/event-registry/waits'
const CHANNELS = '/workflow/management/event-registry/channels'

const eventPath = (key: string) => `${EVENTS}/${encodeURIComponent(key)}`
const channelPath = (key: string) => `${CHANNELS}/${encodeURIComponent(key)}`

export const eventRegistryApi = {
  events: () => apiClient.get<EventModelView[]>(EVENTS),
  saveEvent: (key: string, payload: SaveEventModelCommand) =>
    apiClient.put<EventModelView>(`${eventPath(key)}/draft`, payload),
  publishEvent: (key: string, revision: number) =>
    apiClient.post<EventModelView>(`${eventPath(key)}/publish`, null, { params: { revision } }),
  deleteEvent: (key: string) => apiClient.delete<void>(eventPath(key)),
  usages: (key: string) => apiClient.get<EventUsagesView>(`${eventPath(key)}/usages`),
  waits: (eventKey: string, page: number, size: number) =>
    apiClient.get<PageResult<EventWaitView>>(WAITS, { params: { eventKey, page, size } }),
  channels: () => apiClient.get<RestChannelView[]>(CHANNELS),
  saveChannel: (key: string, payload: SaveRestChannelCommand) =>
    apiClient.put<RestChannelView>(`${channelPath(key)}/draft`, payload),
  publishChannel: (key: string, revision: number) =>
    apiClient.post<RestChannelView>(`${channelPath(key)}/publish`, null, { params: { revision } }),
  deleteChannel: (key: string) => apiClient.delete<void>(channelPath(key)),
}
