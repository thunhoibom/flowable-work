export interface ServiceInputParameter {
  name: string
  type: string
  required: boolean
  location: string
}

export interface ServiceOutputParameter {
  name: string
  type: string
  jsonField: string
}

export interface ServiceInputRow extends ServiceInputParameter {
  expression: string
}

export interface ServiceOutputRow extends ServiceOutputParameter {
  variable: string
}

const PARAMETER_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/

export function buildInputRows(
  parameters: ServiceInputParameter[],
  saved: Array<{ name: string; expression: string }>,
): ServiceInputRow[] {
  return parameters.map((parameter) => {
    const match = saved.find((entry) => entry.name === parameter.name)
    return {
      ...parameter,
      expression: match ? match.expression : `\${${parameter.name}}`,
    }
  })
}

export function buildOutputRows(
  parameters: ServiceOutputParameter[],
  saved: Array<{ name: string; variable: string }>,
): ServiceOutputRow[] {
  return parameters.map((parameter) => {
    const match = saved.find((entry) => entry.name === parameter.name)
    return {
      ...parameter,
      variable: match ? match.variable : parameter.name,
    }
  })
}

export function missingRequired(
  rows: Array<{ name: string; required: boolean; expression: string }>,
): string[] {
  return rows.filter((row) => row.required && row.expression.trim() === '').map((row) => row.name)
}

export function normalizeOutputVariable(
  candidate: string,
  previous: string,
  parameterName: string,
): string {
  if (PARAMETER_NAME.test(candidate)) return candidate
  if (PARAMETER_NAME.test(previous)) return previous
  return parameterName
}
