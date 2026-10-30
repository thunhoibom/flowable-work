export function showsApprovalMode(isTask: boolean, isServiceTask: boolean) {
  return isTask && !isServiceTask
}
