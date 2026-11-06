export function showsApprovalMode(isTask: boolean, isAutomaticTask: boolean) {
  return isTask && !isAutomaticTask
}
