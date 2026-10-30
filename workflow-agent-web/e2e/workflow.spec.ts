import { expect, test, type Page } from '@playwright/test'
import { agentsEnabled } from '../src/config/product'

const agentAdminTest = agentsEnabled ? test : test.skip
import AxeBuilder from '@axe-core/playwright'

async function mockApi(page: Page) {
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const url = new URL(route.request().url())
      let data: unknown = null
      if (url.pathname.endsWith('/auth/me')) {
        data = {
          userId: 'admin-id',
          username: 'admin',
          displayName: '平台管理员',
          tenantCode: 'default',
          roles: ['PLATFORM_ADMIN'],
          permissions: [],
        }
      } else if (url.pathname.endsWith('/auth/csrf')) {
        data = 'e2e-csrf-token'
      } else if (url.pathname.endsWith('/auth/tenants')) {
        data = [
          {
            tenantId: 'default',
            tenantCode: 'default',
            tenantName: '默认租户',
            enabled: true,
            current: true,
            roles: ['PLATFORM_ADMIN'],
          },
        ]
      } else if (url.pathname.endsWith('/definitions/page')) {
        data = {
          total: 1,
          pageNum: 1,
          pageSize: 20,
          records: [
            {
              processDefinitionId: 'expense:2:100',
              processDefinitionKey: 'expense_approval',
              processDefinitionName: '费用审批',
              latestVersion: 2,
              latestDeployTime: '2026-08-01T08:00:00+08:00',
              activeVersion: 2,
              publishStatus: 'published',
              tenantId: 'default',
            },
          ],
        }
      } else if (url.pathname.endsWith('/process/start')) {
        data = {
          processInstanceId: 'instance-200',
          processDefinitionId: 'expense:2:100',
          processDefinitionKey: 'expense_approval',
          businessKey: 'EXP-2026-002',
          activeTasks: [],
        }
      } else if (url.pathname.endsWith('/process/interaction')) {
        data = { fields: [], agentActivityIds: [] }
      } else if (url.pathname.endsWith('/process/participant-requirements')) {
        const payload = route.request().postDataJSON() as {
          variables?: Record<string, unknown>
        }
        data =
          payload.variables?.manualParticipants === 'yes'
            ? [
                {
                  activityId: 'managerApproval',
                  activityName: '主管审批',
                  assignmentType: 'ASSIGNEE',
                  multiple: false,
                  required: true,
                },
              ]
            : [
                {
                  activityId: 'managerApproval',
                  activityName: '主管审批',
                  assignmentType: 'ASSIGNEE',
                  multiple: false,
                  required: false,
                },
              ]
      } else if (url.pathname.endsWith('/task/participant-requirements')) {
        data = [
          {
            activityId: 'hrReview',
            activityName: '人事复核',
            assignmentType: 'CANDIDATE_USERS',
            multiple: true,
            required: true,
          },
        ]
      } else if (url.pathname.endsWith('/task/interaction')) {
        data = { fields: [], agentActivityIds: [] }
      } else if (url.pathname.endsWith('/auth/directory/users')) {
        data = {
          total: 2,
          pageNum: 1,
          pageSize: 10,
          records: [
            { userId: 'admin-id', username: 'admin', displayName: '平台管理员' },
            { userId: 'alice-id', username: 'alice', displayName: '试用用户' },
          ],
        }
      } else if (url.pathname.endsWith('/agent-providers/enabled')) {
        data = [
          {
            id: 31,
            code: 'mock_local',
            name: '本地 Mock',
            type: 'MOCK',
            enabled: true,
            credentialConfigured: false,
          },
        ]
      } else if (url.pathname.endsWith('/agent-providers')) {
        const empty = url.searchParams.get('keyword') === '__empty__'
        data = {
          total: empty ? 0 : 1,
          pageNum: 1,
          pageSize: 20,
          records: empty
            ? []
            : [
                {
                  id: 31,
                  code: 'mock_local',
                  name: '本地 Mock',
                  type: 'MOCK',
                  enabled: true,
                  credentialConfigured: false,
                  createdAt: '2026-08-08T08:00:00+08:00',
                  updatedAt: '2026-08-08T08:00:00+08:00',
                },
              ],
        }
      } else if (url.pathname.endsWith('/agents/published-versions')) {
        data = {
          total: 1,
          pageNum: 1,
          pageSize: 30,
          records: [
            {
              id: 51,
              definitionId: 41,
              agentCode: 'expense_reviewer',
              agentName: '费用审核 Agent',
              version: 1,
              executionMode: 'MODEL_ONLY',
              timeoutSeconds: 120,
              inputSchema: '{"type":"object","properties":{"customer":{"type":"string"}}}',
              outputSchema: '{"type":"object","properties":{"decision":{"type":"string"}}}',
              contractFingerprint: 'test-contract-fingerprint',
            },
          ],
        }
      } else if (url.pathname.endsWith('/agents')) {
        const empty = url.searchParams.get('keyword') === '__empty__'
        data = {
          total: empty ? 0 : 1,
          pageNum: 1,
          pageSize: 20,
          records: empty
            ? []
            : [
                {
                  id: 41,
                  code: 'expense_reviewer',
                  name: '费用审核 Agent',
                  description: '检查费用说明并给出结构化结论',
                  enabled: true,
                  latestVersion: 1,
                  publishedVersion: 1,
                  createdAt: '2026-08-08T08:00:00+08:00',
                  updatedAt: '2026-08-08T08:00:00+08:00',
                },
              ],
        }
      } else if (url.pathname.endsWith('/agent-runs/manual-tests')) {
        data = { runId: 91, status: 'QUEUED' }
      } else if (url.pathname.endsWith('/agent-runs/91')) {
        const run = {
          id: 91,
          agentCode: 'expense_reviewer',
          agentName: '费用审核 Agent',
          agentVersion: 1,
          status: 'SUCCEEDED',
          resultStatus: 'SUCCESS',
          processInstanceId: 'instance-100',
          activityId: 'agentReview',
          deadlineAt: '2026-08-08T08:02:00+08:00',
          startedAt: '2026-08-08T08:00:01+08:00',
          completedAt: '2026-08-08T08:00:03+08:00',
          createdAt: '2026-08-08T08:00:00+08:00',
          updatedAt: '2026-08-08T08:00:03+08:00',
        }
        data = {
          run,
          payload: {
            inputSnapshotJson: '{"input":"审核差旅费用"}',
            outputSnapshotJson: '{"content":"建议通过"}',
          },
          attempts: [
            {
              id: 92,
              attemptNo: 1,
              status: 'SUCCEEDED',
              startedAt: run.startedAt,
              completedAt: run.completedAt,
              createdAt: run.createdAt,
              updatedAt: run.updatedAt,
            },
          ],
          steps: [],
          modelInvocations: [
            {
              id: 94,
              attemptId: 92,
              stepId: 95,
              providerName: '本地 Mock',
              requestedModel: 'mock-model',
              actualModel: 'mock-model',
              status: 'SUCCEEDED',
              inputTokens: 0,
              outputTokens: 0,
              reasoningTokens: 0,
              latencyMillis: 0,
              createdAt: run.createdAt,
              completedAt: run.completedAt,
            },
          ],
          checkpoints: [],
          stateHistory: [
            {
              id: 93,
              attemptId: 92,
              oldStatus: 'RUNNING',
              newStatus: 'SUCCEEDED',
              reasonCode: 'RESULT_ACCEPTED',
              operatorType: 'WORKER',
              operatorId: 'worker-1',
              traceId: 'trace-91',
              createdAt: run.completedAt,
            },
          ],
        }
      } else if (url.pathname.endsWith('/agent-runs')) {
        const empty = url.searchParams.get('keyword') === '__empty__'
        data = {
          total: empty ? 0 : 1,
          pageNum: 1,
          pageSize: 20,
          records: empty
            ? []
            : [
                {
                  id: 91,
                  agentCode: 'expense_reviewer',
                  agentName: '费用审核 Agent',
                  agentVersion: 1,
                  status: 'SUCCEEDED',
                  resultStatus: 'SUCCESS',
                  processInstanceId: 'instance-100',
                  activityId: 'agentReview',
                  deadlineAt: '2026-08-08T08:02:00+08:00',
                  startedAt: '2026-08-08T08:00:01+08:00',
                  completedAt: '2026-08-08T08:00:03+08:00',
                  createdAt: '2026-08-08T08:00:00+08:00',
                  updatedAt: '2026-08-08T08:00:03+08:00',
                },
              ],
        }
      } else if (url.pathname.endsWith('/task/approve')) {
        data = {
          completedTaskId: 'task-200',
          processInstanceId: 'instance-200',
          processEnded: true,
          nextTasks: [],
        }
      } else if (url.pathname.endsWith('/diagram-data')) {
        data = {
          bpmnXml: `<?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
              xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
              xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
              targetNamespace="http://workflow-agent.local">
              <process id="expense_approval" isExecutable="true">
                <startEvent id="start"><outgoing>flow1</outgoing></startEvent>
                <userTask id="managerApproval" name="主管审批">
                  <incoming>flow1</incoming><outgoing>flow2</outgoing>
                </userTask>
                <endEvent id="end"><incoming>flow2</incoming></endEvent>
                <sequenceFlow id="flow1" sourceRef="start" targetRef="managerApproval" />
                <sequenceFlow id="flow2" sourceRef="managerApproval" targetRef="end" />
              </process>
              <bpmndi:BPMNDiagram id="diagram">
                <bpmndi:BPMNPlane id="plane" bpmnElement="expense_approval">
                  <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                    <dc:Bounds x="120" y="120" width="36" height="36" />
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="manager_di" bpmnElement="managerApproval">
                    <dc:Bounds x="220" y="98" width="100" height="80" />
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                    <dc:Bounds x="380" y="120" width="36" height="36" />
                  </bpmndi:BPMNShape>
                  <bpmndi:BPMNEdge id="flow1_di" bpmnElement="flow1">
                    <di:waypoint x="156" y="138" /><di:waypoint x="220" y="138" />
                  </bpmndi:BPMNEdge>
                  <bpmndi:BPMNEdge id="flow2_di" bpmnElement="flow2">
                    <di:waypoint x="320" y="138" /><di:waypoint x="380" y="138" />
                  </bpmndi:BPMNEdge>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </definitions>`,
          completedActivityIds: ['start'],
          activeActivityIds: ['managerApproval'],
          highlightedFlows: ['flow1'],
          activityDetails: {},
        }
      } else if (url.pathname.endsWith('/instance/detail')) {
        data = {
          instance: {
            processInstanceId: 'instance-200',
            processDefinitionId: 'expense:2:100',
            processDefinitionKey: 'expense_approval',
            processDefinitionName: '费用审批',
            businessKey: 'EXP-2026-002',
            startUserId: 'admin',
            startTime: '2026-08-02T08:00:00+08:00',
            status: 'RUNNING',
            tenantId: 'default',
          },
          tasks: [
            {
              taskId: 'task-200',
              taskDefinitionKey: 'managerApproval',
              taskName: '主管审批',
              assignee: 'admin',
              candidateUsers: [],
              candidateGroups: [],
              status: 'RUNNING',
              startTime: '2026-08-02T08:00:00+08:00',
            },
          ],
          variables: [],
        }
      } else if (url.pathname.endsWith('/instances/page')) {
        data = {
          total: 1,
          pageNum: 1,
          pageSize: 20,
          records: [
            {
              processInstanceId: 'instance-100',
              processDefinitionName: '费用审批',
              businessKey: 'EXP-2026-001',
              startUserId: 'alice',
              startTime: '2026-08-01T08:00:00+08:00',
              status: 'RUNNING',
            },
          ],
        }
      } else if (url.pathname.endsWith('/node-assignment-rule')) {
        data = { total: 0, pageNum: 1, pageSize: 20, records: [] }
      } else if (url.pathname.endsWith('/tenant')) {
        data = {
          total: 1,
          pageNum: 1,
          pageSize: 20,
          records: [
            {
              id: 1,
              tenantId: 'default',
              tenantCode: 'DEFAULT',
              tenantName: '默认租户',
              enabled: true,
            },
          ],
        }
      } else if (url.pathname.endsWith('/definition')) {
        data = {
          processDefinitionId: 'expense:2:100',
          processDefinitionKey: 'expense_approval',
          processDefinitionName: '费用审批',
          version: 2,
          deploymentId: 'deployment-100',
          deployedAt: '2026-08-01T08:00:00+08:00',
          tenantId: 'default',
          active: true,
          bpmnXml: `<?xml version="1.0"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:flowable="http://flowable.org/bpmn">
              <process id="expense_approval">
                <startEvent id="start" />
                <userTask id="managerApproval" name="主管审批"
                  flowable:assignee="\${assigneeService.getAssignee(execution)}" />
              </process>
            </definitions>`,
        }
      } else if (url.pathname.endsWith('/definitions')) {
        data = [
          {
            processDefinitionId: 'expense:2:100',
            processDefinitionKey: 'expense_approval',
            processDefinitionName: '费用审批',
            version: 2,
            deploymentId: 'deployment-100',
            deployedAt: '2026-08-01T08:00:00+08:00',
            tenantId: 'default',
            active: true,
            bpmnXml: '<?xml version="1.0"?><definitions />',
          },
        ]
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SUCCESS', message: 'success', data }),
      })
    },
  )
}

test.beforeEach(async ({ page }) => {
  page.on('pageerror', (error) => console.error(`pageerror: ${error.message}`))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) {
      console.error(`console ${message.type()}: ${message.text()}`)
    }
  })
  await page.addInitScript(() => {
    localStorage.setItem(
      'workflow-agent.auth-session',
      JSON.stringify({
        userId: 'admin-id',
        username: 'admin',
        displayName: '平台管理员',
        tenantCode: 'default',
        roles: ['PLATFORM_ADMIN'],
        permissions: [],
        tokenType: 'Bearer',
        accessToken: 'e2e-access-token',
        expiresIn: 3600,
        expiresAt: '2099-01-01T00:00:00Z',
      }),
    )
  })
  await mockApi(page)
})

test('keeps the process definition workspace accessible', async ({ page }) => {
  await page.goto('/process-definitions')
  await expect(page.getByRole('main')).toBeVisible()
  await expect(page.getByRole('heading', { level: 1, name: 'Quy trình' })).toBeVisible()
  await expect(page.locator('.el-select__input[aria-label="Trạng thái"]')).toHaveCount(1)
  const accessibilityScan = await new AxeBuilder({ page }).analyze()
  expect(accessibilityScan.violations).toEqual([])
})

test('supports a non-overlapping collapsible desktop navigation', async ({ page }) => {
  await page.goto('/process-definitions')
  const shell = page.locator('.app-shell')
  const collapseButton = page.getByRole('button', { name: 'Thu gọn menu' })
  await expect(collapseButton).toBeVisible()
  await collapseButton.click()
  await expect(shell).toHaveClass(/is-sidebar-collapsed/)
  await expect(page.getByRole('button', { name: 'Mở rộng menu' })).toHaveAttribute(
    'aria-expanded',
    'false',
  )
  await expect(page.locator('.sidebar .nav-item').first()).toBeVisible()
  await page.getByRole('button', { name: 'Mở rộng menu' }).click()
  await expect(shell).not.toHaveClass(/is-sidebar-collapsed/)
})

test('manages process definitions from the workspace', async ({ page }) => {
  await page.goto('/process-definitions')
  await expect(page.getByRole('heading', { level: 1, name: 'Quy trình' })).toBeVisible()
  await expect(page.getByText('费用审批')).toBeVisible()
  await expect(page.getByRole('row', { name: /expense_approval/ })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Tạo quy trình' }).first()).toBeVisible()

  await page.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const startDialog = page.getByRole('dialog', { name: 'Bắt đầu quy trình' })
  await expect(startDialog.getByText(/费用审批.*Phiên bản 2/)).toBeVisible()
  await startDialog.getByLabel('Mã hồ sơ').fill('EXP-2026-002')
  await startDialog.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  await expect(page).toHaveURL(/process-instances\/instance-200/)

  await page.getByRole('tab', { name: 'Nhiệm vụ (1)' }).click()
  await page.getByRole('button', { name: 'Đồng ý' }).click()
  const decisionDialog = page.getByRole('dialog', { name: 'Đồng ý nhiệm vụ' })
  await expect(decisionDialog.getByText('人事复核')).toBeVisible()
  await decisionDialog.getByRole('button', { name: 'Chọn người' }).click()
  const picker = page.getByRole('dialog', { name: 'Chọn người cho “人事复核”' })
  await picker.getByRole('button', { name: /平台管理员.*admin/ }).click()
  await picker.getByRole('button', { name: 'Chọn', exact: true }).click()
  const approveRequest = page.waitForRequest(
    (request) => request.url().endsWith('/workflow/task/approve') && request.method() === 'POST',
  )
  await decisionDialog.getByRole('button', { name: 'Đồng ý', exact: true }).click()
  const payload = (await approveRequest).postDataJSON()
  expect(payload).toMatchObject({
    taskId: 'task-200',
    currentAssignee: 'admin',
    participantAssignments: [{ activityId: 'hrReview', usernames: ['admin'] }],
  })
  await expect(page.getByText('Đã đồng ý')).toBeVisible()
  await expect(page.locator('.tracking-canvas')).toHaveCount(0)
  await page.getByRole('tab', { name: 'Tiến độ' }).click()
  await expect(
    page.locator('.tracking-canvas .djs-element[data-element-id="managerApproval"]'),
  ).toBeVisible()
})

test('creates a plain XML workflow in the BPMN designer', async ({ page }) => {
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Tạo quy trình' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Tạo quy trình' })
  await dialog.getByLabel('Mã quy trình').fill('leave_approval')
  await dialog.getByLabel('Tên quy trình').fill('请假审批')
  await dialog.getByRole('button', { name: 'Tạo và thiết kế' }).click()

  await expect(page).toHaveURL(/process-definitions\/designer/)
  await expect(page.locator('.bpmn-canvas .djs-container')).toBeVisible()
  await expect(page.getByLabel('Tên quy trình')).toHaveValue('请假审批')
  await expect(page.getByText('Tổng quan quy trình', { exact: true })).toBeVisible()
  await expect(page.getByText('Quản lý phiên bản', { exact: true })).toBeVisible()
  await expect(page.getByText('Cấu hình phần tử', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Tạo sơ đồ mới' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Lưu phiên bản mới' })).toBeVisible()
  const userTaskPaletteEntry = page.locator('.djs-palette .bpmn-icon-user-task')
  await expect(userTaskPaletteEntry).toHaveAttribute('title', 'Thêm nhiệm vụ')
  await userTaskPaletteEntry.click()
  await page.locator('.bpmn-canvas').click({ position: { x: 460, y: 260 } })
  await expect(page.getByText('Nhiệm vụ', { exact: true })).toBeVisible()
  await expect(page.getByText('flowable:candidateUsers', { exact: true })).toHaveCount(0)
  await expect(page.getByText('${assigneeService.getCandidates(execution)}')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/process-designer-desktop.png', fullPage: true })
})

test('selects participants without exposing Flowable variable names', async ({ page }) => {
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const startDialog = page.getByRole('dialog', { name: 'Bắt đầu quy trình' })
  await startDialog.getByRole('button', { name: 'Thêm dữ liệu' }).click()
  await startDialog.getByPlaceholder('ví dụ amount').fill('manualParticipants')
  await startDialog.getByPlaceholder('Nhập văn bản').fill('yes')

  await expect(startDialog.getByText('Giao việc')).toBeVisible()
  await expect(startDialog.getByText('主管审批')).toBeVisible()
  await expect(startDialog.getByText(/_assignee/)).toHaveCount(0)
  await startDialog.getByRole('button', { name: 'Chọn người' }).click()

  const picker = page.getByRole('dialog', { name: 'Chọn người cho “主管审批”' })
  await picker.getByRole('button', { name: /试用用户.*alice/ }).click()
  await picker.getByRole('button', { name: 'Chọn', exact: true }).click()

  const startRequest = page.waitForRequest(
    (request) => request.url().endsWith('/workflow/process/start') && request.method() === 'POST',
  )
  await startDialog.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const payload = (await startRequest).postDataJSON()
  expect(payload.participantAssignments).toEqual([
    { activityId: 'managerApproval', usernames: ['alice'] },
  ])
})

test('collects Agent input through the generated workflow contract', async ({ page }) => {
  await page.route('**/api/workflow/process/interaction', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        message: 'success',
        data: {
          agentActivityIds: ['agentReview'],
          fields: [
            {
              variablePath: 'order.customer.name',
              label: '客户名称',
              description: '请输入本次申请对应的客户名称',
              dataType: 'string',
              required: true,
              agentActivityId: 'agentReview',
              agentActivityName: '智能审核',
              agentInputPath: 'customer.name',
            },
          ],
        },
      }),
    })
  })
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const startDialog = page.getByRole('dialog', { name: 'Bắt đầu quy trình' })
  await expect(startDialog.getByText('Thông tin cho Agent')).toBeVisible()
  await startDialog.getByLabel('客户名称').fill('示例客户')

  const startRequest = page.waitForRequest(
    (request) => request.url().endsWith('/workflow/process/start') && request.method() === 'POST',
  )
  await startDialog.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const payload = (await startRequest).postDataJSON()
  expect(payload.variables).toMatchObject({ order: { customer: { name: '示例客户' } } })
})

test('keeps a stable explanation when the Agent contract has no user input', async ({ page }) => {
  await page.route('**/api/workflow/process/interaction', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'SUCCESS',
        message: 'success',
        data: { agentActivityIds: ['agentReview'], fields: [] },
      }),
    })
  })
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const explanation = page.getByText(
    'Bước Agent tiếp theo không cần bạn điền thêm. Công việc sẽ chạy theo quy trình đã phát hành.',
  )
  await expect(explanation).toBeVisible()
})

test('allows a ruled process to fall back when no participant is specified', async ({ page }) => {
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const startDialog = page.getByRole('dialog', { name: 'Bắt đầu quy trình' })

  await expect(startDialog.getByText('Không bắt buộc')).toBeVisible()
  await expect(startDialog.getByText('Chưa chọn. Hệ thống sẽ tự giao.')).toBeVisible()

  const startRequest = page.waitForRequest(
    (request) => request.url().endsWith('/workflow/process/start') && request.method() === 'POST',
  )
  await startDialog.getByRole('button', { name: 'Bắt đầu', exact: true }).click()
  const payload = (await startRequest).postDataJSON()
  expect(payload.participantAssignments).toEqual([])
})

test('navigates across instance, assignment and tenant domains', async ({ page }) => {
  await page.goto('/process-instances')
  await expect(page.getByText('EXP-2026-001')).toBeVisible()
  await page.getByRole('link', { name: 'Phân công' }).click()
  await expect(page.getByRole('button', { name: 'Thêm quy tắc' })).toBeVisible()
  await page.getByRole('link', { name: 'Tổ chức' }).click()
  await expect(page.getByRole('row', { name: /默认租户/ })).toBeVisible()
})

agentAdminTest('manages Agent definitions and inspects the execution ledger', async ({ page }) => {
  await page.goto('/agents')
  await expect(page.getByRole('heading', { level: 1, name: 'Agent' })).toBeVisible()
  const definitionPanel = page.getByLabel('Agent', { exact: true })
  await expect(definitionPanel.getByText('费用审核 Agent')).toBeVisible()
  await expect(definitionPanel.getByRole('row', { name: /费用审核 Agent.*Phiên bản 1/ })).toBeVisible()

  const manualRunRequest = page.waitForRequest((request) =>
    request.url().endsWith('/agent-runs/manual-tests'),
  )
  await definitionPanel.getByRole('button', { name: 'Chạy thử' }).click()
  const manualRunDialog = page.getByRole('dialog', { name: /Chạy thử.*费用审核 Agent/ })
  await manualRunDialog.getByLabel('Nội dung thử').fill('审核差旅费用')
  await manualRunDialog.getByRole('button', { name: 'Bắt đầu thử' }).click()
  expect((await manualRunRequest).postDataJSON()).toEqual({
    definitionId: 41,
    input: '审核差旅费用',
  })
  const manualRunDrawer = page.getByRole('dialog', { name: 'Chi tiết lần chạy #91' })
  await expect(manualRunDrawer.getByText('建议通过').first()).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('tab', { name: 'Cấu hình nhà cung cấp' }).click()
  await expect(
    page.getByLabel('Cấu hình nhà cung cấp').getByRole('row', { name: /本地 Mock.*mock_local/ }),
  ).toBeVisible()

  await page.getByRole('tab', { name: 'Nhật ký chạy' }).click()
  const runPanel = page.getByLabel('Nhật ký chạy')
  await expect(runPanel.getByRole('row', { name: /instance-100/ })).toBeVisible()
  await runPanel.getByRole('button', { name: 'Chi tiết' }).click()
  const drawer = page.getByRole('dialog', { name: 'Chi tiết lần chạy #91' })
  await expect(drawer.getByText('Lần thử (1)')).toBeVisible()
  await drawer.getByRole('tab', { name: 'Lịch sử trạng thái (1)' }).click()
  const stateTransition = drawer.locator('.state-transition')
  await expect(stateTransition.getByTitle('RUNNING')).toHaveText('Đang chạy')
  await expect(stateTransition.getByTitle('SUCCEEDED')).toHaveText('Thành công')
  await expect(drawer.getByText('Trace ID: trace-91')).toBeVisible()
})

agentAdminTest('keeps Agent list pagination anchored when result sets are empty', async ({ page }) => {
  await page.goto('/agents')

  const cases = [
    { tab: 'Agent', emptyTitle: 'Chưa tạo Agent' },
    { tab: 'Cấu hình nhà cung cấp', emptyTitle: 'Chưa cấu hình nhà cung cấp' },
    { tab: 'Nhật ký chạy', emptyTitle: 'Chưa có nhật ký chạy' },
  ]

  for (const item of cases) {
    await page.getByRole('tab', { name: item.tab }).click()
    const panel = page.getByLabel(item.tab)
    const pagination = panel.locator('.table-pagination')
    const before = await pagination.boundingBox()
    await panel.getByLabel('Từ khóa').fill('__empty__')
    await panel.getByRole('button', { name: 'Tìm' }).click()
    await expect(panel.getByText(item.emptyTitle, { exact: true })).toBeVisible()
    const after = await pagination.boundingBox()

    expect(before).not.toBeNull()
    expect(after).not.toBeNull()
    expect(Math.abs(after!.y - before!.y)).toBeLessThanOrEqual(1)
  }
})

test('queries tenant users when configuring an assignment rule', async ({ page }) => {
  await page.goto('/assignment-rules')
  await page.getByRole('button', { name: 'Thêm quy tắc' }).click()
  const dialog = page.getByRole('dialog', { name: 'Thêm phân công' })

  await dialog.getByLabel('Quy trình').click()
  await page.getByRole('option', { name: /费用审批/ }).click()
  await dialog.getByLabel('Phiên bản').click()
  await page.getByRole('option', { name: /Phiên bản 2/ }).click()
  await dialog.getByLabel('Bước').click()
  await page.getByRole('option', { name: /主管审批/ }).click()

  await dialog.getByRole('button', { name: 'Tìm và chọn' }).click()
  const picker = page.getByRole('dialog', { name: 'Chọn người nhận' })
  await picker.getByPlaceholder('Tìm tên đăng nhập hoặc tên hiển thị').fill('alice')
  const searchRequest = page.waitForRequest(
    (request) =>
      request.url().includes('/auth/directory/users') &&
      new URL(request.url()).searchParams.get('keyword') === 'alice',
  )
  await picker.getByRole('button', { name: 'Tìm kiếm' }).click()
  await searchRequest
  await picker.getByRole('button', { name: /试用用户.*alice/ }).click()
  await picker.getByRole('button', { name: 'Chọn', exact: true }).click()
  await expect(dialog.getByText('alice', { exact: true })).toBeVisible()

  const saveRequest = page.waitForRequest((request) =>
    request.url().endsWith('/workflow/node-assignment-rule'),
  )
  await dialog.getByRole('button', { name: 'Lưu' }).click()
  expect((await saveRequest).postDataJSON()).toMatchObject({
    processDefinitionId: 'expense:2:100',
    taskDefinitionKey: 'managerApproval',
    assignmentType: 'ASSIGNEE',
    assignees: ['alice'],
  })
})

test('uses the compact navigation on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Mở menu' }).click()
  const mobileDrawer = page.getByRole('dialog')
  await expect(mobileDrawer.getByRole('link', { name: 'Công việc' })).toBeVisible()
  await expect(mobileDrawer.locator('.mobile-tenant-switcher')).toBeVisible()
  await expect(mobileDrawer.getByRole('button', { name: 'Đăng xuất' })).toBeVisible()
  await mobileDrawer.getByRole('link', { name: 'Công việc' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Công việc' })).toBeVisible()
  await expect(page.getByRole('dialog')).toBeHidden()
  await page.screenshot({ path: 'test-results/process-instances-mobile.png', fullPage: true })
})

test('keeps designer operations and properties available on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/process-definitions')
  await page.getByRole('button', { name: 'Tạo quy trình' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Tạo quy trình' })
  await dialog.getByLabel('Mã quy trình').fill('mobile_approval')
  await dialog.getByLabel('Tên quy trình').fill('移动审批')
  await dialog.getByRole('button', { name: 'Tạo và thiết kế' }).click()

  await expect(page.getByRole('button', { name: 'Thêm thao tác sơ đồ' })).toBeVisible()
  await page.getByRole('button', { name: 'Thêm thao tác sơ đồ' }).click()
  await expect(page.getByRole('menuitem', { name: 'Nhập sơ đồ' })).toBeVisible()
  await expect(page.getByText('Tổng quan quy trình', { exact: true })).toBeVisible()
})
