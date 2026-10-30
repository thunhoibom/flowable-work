package io.github.illuseahashmap.serviceregistry.interfaces;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.illuseahashmap.serviceregistry.domain.InMemoryServiceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServiceCatalogControllerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ServiceCatalogController(new InMemoryServiceCatalog()))
            .build();

    @Test
    void hidesSecretAndReturnsPublishedUrl() throws Exception {
        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"crm\",\"name\":\"CRM\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/services/crm/operations/createLead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "POST",
                                  "url": "https://crm.example/leads",
                                  "authType": "BEARER",
                                  "secret": "s3cret",
                                  "timeoutMillis": 1000,
                                  "errorStatusCodes": ["409"],
                                  "bpmnErrorCode": "LEAD_CONFLICT"
                                }
                                """))
                .andExpect(status().isOk());

        String catalog = mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations[0].secretPresent").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(catalog.contains("s3cret"));

        mvc.perform(post("/services/crm/publish"))
                .andExpect(status().isOk());
        mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations[0].publishedUrl").value("https://crm.example/leads"));
    }

    @Test
    void returnsDraftAndPublishedParametersAndRejectsPathThatIsOptional() throws Exception {
        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"crm\",\"name\":\"CRM\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/services/crm/operations/createLead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "POST",
                                  "url": "https://crm.example/orders/${orderId}",
                                  "authType": "NONE",
                                  "bodyTemplate": "{}",
                                  "timeoutMillis": 1000,
                                  "errorStatusCodes": ["409"],
                                  "bpmnErrorCode": "HTTP_ERROR",
                                  "inputParameters": [
                                    {"name": "orderId", "type": "STRING", "required": true, "location": "PATH"}
                                  ],
                                  "outputParameters": [
                                    {"name": "id", "type": "LONG", "jsonField": "id"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/services/crm/publish")).andExpect(status().isOk());
        mvc.perform(put("/services/crm/operations/createLead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "POST",
                                  "url": "https://crm.example/orders/${orderId}",
                                  "authType": "NONE",
                                  "timeoutMillis": 1000,
                                  "bpmnErrorCode": "HTTP_ERROR",
                                  "inputParameters": [
                                    {"name": "orderId", "type": "STRING", "required": false, "location": "PATH"}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations[0].inputParameters[0].name").value("orderId"))
                .andExpect(jsonPath("$.data.operations[0].publishedInputParameters[0].required").value(true))
                .andExpect(jsonPath("$.data.operations[0].publishedMethod").value("POST"))
                .andExpect(jsonPath("$.data.operations[0].publishedBpmnErrorCode").value("HTTP_ERROR"));
        mvc.perform(get("/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].operations[0].publishedOutputParameters[0].jsonField").value("id"));
    }
}
