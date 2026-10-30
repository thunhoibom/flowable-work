export function pathRequirement(location: string, required: boolean) {
  if (location === 'PATH') {
    return { required: true, locked: true }
  }
  return { required, locked: false }
}
