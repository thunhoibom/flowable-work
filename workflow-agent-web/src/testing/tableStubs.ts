import { defineComponent, h, inject, provide, type PropType, type VNode } from 'vue'

/**
 * Light stand-ins for el-table and el-table-column. They render one `[data-row]` element per row
 * and call each column's default slot with `{ row, $index }`, so specs can find cells and buttons
 * without the real table layout.
 */
const ROW = Symbol('table-row')

interface RowContext {
  row: unknown
  index: number
}

const TableRow = defineComponent({
  props: {
    row: { type: null, required: true },
    index: { type: Number, required: true },
  },
  setup(props, { slots }) {
    provide<RowContext>(ROW, props)
    return () => h('div', { 'data-row': '' }, slots.default?.())
  },
})

export const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array as PropType<unknown[]>, default: () => [] } },
  setup(props, { slots }) {
    return (): VNode =>
      h(
        'div',
        { class: 'el-table-stub' },
        props.data.length
          ? props.data.map((row, index) =>
              h(TableRow, { key: index, row, index }, { default: () => slots.default?.() }),
            )
          : slots.empty?.(),
      )
  },
})

export const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { label: { type: String, default: '' }, prop: { type: String, default: '' } },
  setup(props, { slots }) {
    const context = inject<RowContext | null>(ROW, null)
    return () => {
      if (!context) return null
      const value = slots.default
        ? slots.default({ row: context.row, $index: context.index })
        : props.prop
          ? String((context.row as Record<string, unknown>)[props.prop] ?? '')
          : ''
      return h('div', { 'data-column': props.label }, value)
    }
  },
})

export const tableStubs = {
  'el-table': ElTableStub,
  'el-table-column': ElTableColumnStub,
}
