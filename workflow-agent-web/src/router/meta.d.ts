import 'vue-router'
import type { AppRole } from '@/features/auth'

export type NavigationIcon =
  | 'definitions'
  | 'instances'
  | 'assignments'
  | 'agents'
  | 'services'
  | 'access'
  | 'tenants'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    public?: boolean
    immersive?: boolean
    /** Roles allowed on this screen; mirrors the backend `hasAnyRole(...)`. */
    requiredAnyRoles?: readonly AppRole[]
    /**
     * Menu entry. The item is shown when the user's EIM sitemap contains `menuCode`; its
     * label and position come from EIM (`label`/`order` are fallbacks).
     */
    navigation?: {
      menuCode: string
      label: string
      icon: NavigationIcon
      order: number
    }
  }
}
