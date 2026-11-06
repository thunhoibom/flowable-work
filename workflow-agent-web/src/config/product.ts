/**
 * Agent stays in the codebase so it can return when this product grows into a full
 * Flowable Work. While this is false, the menu, the Agent page, and the designer
 * settings stay hidden. Existing processes that already contain an Agent step still run.
 */
export const agentsEnabled = false

/** Shows the script step in the palette. The backend flag workflow.script-task.enabled still decides. */
export const scriptTasksEnabled = import.meta.env.VITE_SCRIPT_TASKS_ENABLED === 'true'

/**
 * Shows "call another process" in the palette and replace menu.
 * The backend flag workflow.subprocess.call-activity-enabled still decides at publish time.
 */
export const callActivityEnabled = import.meta.env.VITE_CALL_ACTIVITY_ENABLED === 'true'
