import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ListEmptyState from '@/components/ListEmptyState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { getStatusPresentation } from '@/utils/status'

describe('shared data presentation', () => {
  it('maps technical lifecycle states to Vietnamese labels', () => {
    expect(getStatusPresentation('RUNNING')).toEqual({ label: 'Đang chạy', tone: 'primary' })
    expect(getStatusPresentation('FAILED')).toEqual({ label: 'Thất bại', tone: 'danger' })
    expect(getStatusPresentation(true)).toEqual({ label: 'Đang bật', tone: 'success' })
    expect(getStatusPresentation(false)).toEqual({ label: 'Đã tắt', tone: 'info' })
    expect(getStatusPresentation('DRAFT')).toEqual({ label: 'Bản nháp', tone: 'warning' })
    expect(getStatusPresentation('PUBLISHED')).toEqual({ label: 'Đã phát hành', tone: 'success' })
    expect(getStatusPresentation('QUEUED')).toEqual({ label: 'Đang chờ', tone: 'primary' })
    expect(getStatusPresentation('PENDING')).toEqual({ label: 'Sắp chạy', tone: 'info' })
    expect(getStatusPresentation('ACTIVE')).toEqual({ label: 'Cần làm', tone: 'primary' })
    expect(getStatusPresentation('SUCCEEDED')).toEqual({ label: 'Thành công', tone: 'success' })
    expect(getStatusPresentation('SUCCESS')).toEqual({ label: 'Thành công', tone: 'success' })
    expect(getStatusPresentation('COMPLETED')).toEqual({ label: 'Đã hoàn thành', tone: 'success' })
    expect(getStatusPresentation('TIMED_OUT')).toEqual({ label: 'Quá hạn', tone: 'danger' })
    expect(getStatusPresentation('CANCELLED')).toEqual({ label: 'Đã hủy', tone: 'info' })
    expect(getStatusPresentation('SKIPPED')).toEqual({ label: 'Đã bỏ qua', tone: 'info' })
    expect(getStatusPresentation('EMPTY')).toEqual({ label: 'Không có kết quả', tone: 'warning' })
    expect(getStatusPresentation('PARTIAL')).toEqual({ label: 'Hoàn thành một phần', tone: 'warning' })
    expect(getStatusPresentation('REJECTED')).toEqual({ label: 'Đã từ chối', tone: 'danger' })
    expect(getStatusPresentation('WAITING_FOR_REVIEW')).toEqual({
      label: 'Chờ xem xét',
      tone: 'warning',
    })
    expect(getStatusPresentation('RETRYING')).toEqual({ label: 'Sẽ chạy lại', tone: 'primary' })
    expect(getStatusPresentation('DECIDED')).toEqual({ label: 'Đã quyết định', tone: 'success' })
    expect(getStatusPresentation('NO_SUCH')).toEqual({ label: 'NO_SUCH', tone: 'info' })
    expect(getStatusPresentation('')).toEqual({ label: 'Không rõ', tone: 'info' })
  })

  it('renders a readable status badge while retaining the raw status as its title', () => {
    const wrapper = mount(StatusBadge, {
      props: { status: 'SUCCEEDED' },
      global: { stubs: { 'el-tag': { template: '<span><slot /></span>' } } },
    })

    expect(wrapper.text()).toContain('Thành công')
    expect(wrapper.find('[title="SUCCEEDED"]').exists()).toBe(true)
    expect(wrapper.find('.status-badge__dot').exists()).toBe(false)
  })

  it('renders a shared list empty state with guidance', () => {
    const wrapper = mount(ListEmptyState, {
      props: { title: '暂无运行记录', description: '提交运行后会显示在这里。' },
    })

    expect(wrapper.text()).toContain('暂无运行记录')
    expect(wrapper.text()).toContain('提交运行后会显示在这里。')
  })
})
