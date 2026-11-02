package io.github.illuseahashmap.serviceregistry.interfaces;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Test
    void renamesAnExistingService() throws Exception {
        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"crm\",\"name\":\"CRM\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"crm\",\"name\":\"CRM mới\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("CRM mới"));
    }

    @Test
    void collectionPathIsExact() throws Exception {
        mvc.perform(get("/services/")).andExpect(status().isNotFound());
        mvc.perform(post("/services/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"crm\",\"name\":\"CRM\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"  \",\"name\":\"CRM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteOperationKeepsPublicationUntilRepublishAndDeleteServiceRemovesBoth() throws Exception {
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
                                  "authType": "NONE",
                                  "timeoutMillis": 1000,
                                  "bpmnErrorCode": "HTTP_ERROR"
                                }
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/services/crm/publish")).andExpect(status().isOk());

        mvc.perform(delete("/services/crm/operations/createLead")).andExpect(status().isOk());
        mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations").isEmpty());

        mvc.perform(delete("/services/missing")).andExpect(status().isNotFound());
        mvc.perform(delete("/services/crm")).andExpect(status().isOk());
        mvc.perform(get("/services/crm")).andExpect(status().isNotFound());
    }

    @Test
    void savesConnectionSettingsWithoutReturningTheSecret() throws Exception {
        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"crm\",\"name\":\"CRM\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/services/crm/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "CRM",
                                  "description": "Khách hàng",
                                  "baseUrls": [{"environment": "default", "url": "https://crm.example"}],
                                  "sharedAuth": {"type": "BEARER", "apiKeyHeader": null, "secret": "s3cret"}
                                }
                                """))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("Khách hàng"))
                .andExpect(jsonPath("$.data.baseUrls[0].url").value("https://crm.example"))
                .andExpect(jsonPath("$.data.sharedAuth.type").value("BEARER"))
                .andExpect(jsonPath("$.data.sharedAuth.secretPresent").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("s3cret"));

        // A blank secret must not wipe the secret stored by the previous save.
        mvc.perform(put("/services/crm/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "CRM",
                                  "description": "Khách hàng",
                                  "baseUrls": [{"environment": "default", "url": "https://crm.example"}],
                                  "sharedAuth": {"type": "BEARER", "apiKeyHeader": null, "secret": ""}
                                }
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/services/crm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sharedAuth.secretPresent").value(true));
    }

    @Test
    void savesPaginationAndRejectsACallbackWithoutATimeout() throws Exception {
        mvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceKey\":\"pages\",\"name\":\"Pages\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/services/pages/operations/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "GET",
                                  "url": "/items",
                                  "authType": "NONE",
                                  "authMode": "NONE",
                                  "timeoutMillis": 1000,
                                  "inputParameters": [
                                    {"name": "page", "type": "STRING", "required": false, "location": "QUERY"}
                                  ],
                                  "outputParameters": [
                                    {"name": "id", "type": "STRING", "jsonField": "id"}
                                  ],
                                  "steps": {
                                    "pagination": "NEXT_TOKEN",
                                    "nextField": "next",
                                    "nextTokenParameter": "page",
                                    "maxPages": 3,
                                    "callStyle": "SYNC"
                                  }
                                }
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/services/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations[0].steps.pagination").value("NEXT_TOKEN"))
                .andExpect(jsonPath("$.data.operations[0].steps.maxPages").value(3));
        mvc.perform(put("/services/pages/operations/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "method": "GET",
                                  "url": "/items",
                                  "authType": "NONE",
                                  "authMode": "NONE",
                                  "timeoutMillis": 1000,
                                  "outputParameters": [
                                    {"name": "id", "type": "STRING", "jsonField": "id"}
                                  ],
                                  "steps": {
                                    "pagination": "NONE",
                                    "callStyle": "CALLBACK",
                                    "correlationOutput": "id"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
