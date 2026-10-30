const DEFAULT_AUTH_REDIRECT = '/process-definitions'

export function resolvePostAuthRedirect(value: unknown) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : DEFAULT_AUTH_REDIRECT
}
