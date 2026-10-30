package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XmlServiceTaskBindingParserTest {

    private final XmlServiceTaskBindingParser parser = new XmlServiceTaskBindingParser();

    @Test
    void parsesServiceCallKeysWithoutUrl() {
        var binding = parser.parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:workflow="http://workflow-agent.local/bpmn">
                  <bpmn:process id="test" isExecutable="true">
                    <bpmn:serviceTask id="createLead">
                      <bpmn:extensionElements>
                        <workflow:serviceCall serviceKey="crm" operationKey="createLead"
                            inputMapping='[{"name":"customerName","location":"BODY"}]'
                            outputMapping='[{"jsonField":"id","variableName":"leadId"}]' />
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                  </bpmn:process>
                </bpmn:definitions>
                """).getFirst();

        assertThat(binding.serviceKey()).isEqualTo("crm");
        assertThat(binding.operationKey()).isEqualTo("createLead");
        assertThat(binding.activityId()).isEqualTo("createLead");
        assertThat(binding.inputMappingJson()).contains("customerName");
        assertThat(binding.outputMappingJson()).contains("leadId");
    }
}
