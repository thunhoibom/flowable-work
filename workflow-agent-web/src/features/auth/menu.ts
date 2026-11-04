import type { RouteRecordNormalized } from 'vue-router'
import type { NavigationIcon } from '@/router/meta'
import { hasAccess } from './authorization'
import type { AuthUser, SitemapEntry } from './types'

export interface NavigationItem {
  routeName: string
  label: string
  icon: NavigationIcon
}

/** Depth-first, in EIM order: the codes of every entry of the sitemap tree, with their names. */
function flatten(entries: SitemapEntry[], into: Map<string, SitemapEntry> = new Map()) {
  for (const entry of entries) {
    if (!into.has(entry.code)) into.set(entry.code, entry)
    flatten(entry.children, into)
  }
  return into
}

/**
 * Menu built from the user's EIM sitemap, as rsa-core-service's portal does: a screen appears
 * when its `menuCode` is in the sitemap, labelled and ordered as in EIM. Screens whose roles the
 * user lacks stay hidden even if EIM lists them, since their API calls would be refused.
 */
export function buildNavigation(
  routes: RouteRecordNormalized[],
  sitemap: SitemapEntry[],
  user: Pick<AuthUser, 'roles'> | null | undefined,
): NavigationItem[] {
  const entries = flatten(sitemap)
  const position = new Map([...entries.keys()].map((code, index) => [code, index]))
  return routes
    .filter(
      (route) =>
        route.name &&
        route.meta.navigation &&
        entries.has(route.meta.navigation.menuCode) &&
        hasAccess(user, route.meta),
    )
    .sort(
      (left, right) =>
        position.get(left.meta.navigation!.menuCode)! -
        position.get(right.meta.navigation!.menuCode)!,
    )
    .map((route) => {
      const navigation = route.meta.navigation!
      return {
        routeName: String(route.name),
        label: entries.get(navigation.menuCode)?.name?.trim() || navigation.label,
        icon: navigation.icon,
      }
    })
}
