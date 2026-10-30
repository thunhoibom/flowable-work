import { ref, type Ref } from 'vue'

export type SchemaField = {
  path: string
  type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array'
  itemType: 'string' | 'number' | 'integer' | 'boolean' | 'object'
  required: boolean
  description: string
}

type SchemaNode = {
  type?: string
  description?: string
  required?: string[]
  items?: SchemaNode
  properties?: Record<string, SchemaNode>
}

const FIELD_TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array'] as const
const ITEM_TYPES = ['string', 'number', 'integer', 'boolean', 'object'] as const

function emptyField(): SchemaField {
  return { path: '', type: 'string', itemType: 'string', required: false, description: '' }
}

function parseSchema(schema?: string): SchemaField[] {
  if (!schema?.trim()) return []
  try {
    const root = JSON.parse(schema) as SchemaNode
    const fields: SchemaField[] = []
    const walk = (node: SchemaNode, prefix = '') => {
      if (!node.properties || typeof node.properties !== 'object') return
      const required = new Set(Array.isArray(node.required) ? node.required : [])
      Object.entries(node.properties).forEach(([name, child]) => {
        const path = prefix ? `${prefix}.${name}` : name
        fields.push({
          path,
          type: FIELD_TYPES.includes(child?.type as (typeof FIELD_TYPES)[number])
            ? (child.type as SchemaField['type'])
            : 'string',
          itemType: ITEM_TYPES.includes(child?.items?.type as (typeof ITEM_TYPES)[number])
            ? (child.items?.type as SchemaField['itemType'])
            : 'string',
          required: required.has(name),
          description: child?.description || '',
        })
        walk(child, path)
      })
    }
    walk(root)
    return fields
  } catch {
    return []
  }
}

function buildSchema(fields: SchemaField[]) {
  const root: SchemaNode = { type: 'object', properties: {} }
  fields
    .filter((field) => field.path.trim())
    .forEach((field) => {
      const segments = field.path
        .split('.')
        .map((segment) => segment.trim())
        .filter(Boolean)
      let node = root
      segments.forEach((segment, index) => {
        node.properties ||= {}
        node.properties[segment] ||= { type: index === segments.length - 1 ? field.type : 'object' }
        if (index === segments.length - 1) {
          node.properties[segment].type = field.type
          if (field.description.trim())
            node.properties[segment].description = field.description.trim()
          if (field.required) {
            node.required ||= []
            if (!node.required.includes(segment)) node.required.push(segment)
          }
          if (field.type === 'array') node.properties[segment].items = { type: field.itemType }
        }
        node = node.properties[segment]
      })
    })
  return fields.length ? JSON.stringify(root) : ''
}

export function useAgentSchemaEditor() {
  const inputSchemaFields = ref<SchemaField[]>([])
  const outputSchemaFields = ref<SchemaField[]>([])

  function reset(inputSchema?: string, outputSchema?: string) {
    inputSchemaFields.value = parseSchema(inputSchema)
    outputSchemaFields.value = parseSchema(outputSchema)
  }

  function addInputSchemaField() {
    inputSchemaFields.value.push(emptyField())
  }

  function addOutputSchemaField() {
    outputSchemaFields.value.push(emptyField())
  }

  function removeInputSchemaField(index: number) {
    inputSchemaFields.value.splice(index, 1)
  }

  return {
    inputSchemaFields: inputSchemaFields as Ref<SchemaField[]>,
    outputSchemaFields: outputSchemaFields as Ref<SchemaField[]>,
    reset,
    buildInputSchema: () => buildSchema(inputSchemaFields.value),
    buildOutputSchema: () => buildSchema(outputSchemaFields.value),
    addInputSchemaField,
    addOutputSchemaField,
    removeInputSchemaField,
  }
}
