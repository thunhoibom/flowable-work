import { describe, it, expect } from 'vitest'

import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '../App.vue'

describe('App', () => {
  it('renders the current route', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<main>Workflow workspace</main>' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(wrapper.text()).toContain('Workflow workspace')
  })

  it('configures Element Plus with the Vietnamese locale', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<main>Workflow workspace</main>' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router] } })

    expect(wrapper.findComponent({ name: 'ElConfigProvider' }).props('locale')).toMatchObject({
      name: 'vi',
    })
  })
})
