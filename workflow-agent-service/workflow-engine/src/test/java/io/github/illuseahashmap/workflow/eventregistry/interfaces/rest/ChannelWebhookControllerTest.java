package io.github.illuseahashmap.workflow.eventregistry.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.eventregistry.application.InboundEventReceiver;
import io.github.illuseahashmap.workflow.eventregistry.application.dto.InboundEventResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChannelWebhookControllerTest {

    private static final String URL = "/platform-api/channel-definitions/key/payWebhook/events";

    private InboundEventReceiver receiver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        receiver = mock(InboundEventReceiver.class);
        EventRegistryProperties properties = new EventRegistryProperties();
        properties.setMaxBodyBytes(16);
        mvc = MockMvcBuilders.standaloneSetup(new ChannelWebhookController(receiver, properties)).build();
    }

    @Test
    void declaredLengthOverTheLimitIsRefused() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"A-1234567\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("EVENT_TOO_LARGE"));
        verify(receiver, never()).receive(any(), anyMap(), any(), any());
    }

    @Test
    void actualBodyOverTheLimitIsRefusedWithoutContentLength() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setContent("{\"orderId\":\"A-1234567\"}".getBytes());
                            request.removeHeader("Content-Length");
                            return request;
                        }))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void errorResultKeepsItsStatusAndCode() throws Exception {
        when(receiver.receive(eq("payWebhook"), anyMap(), any(), eq("k")))
                .thenReturn(InboundEventResult.fail(409, "NO_WAITING_INSTANCE", "Không có"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_WAITING_INSTANCE"))
                .andExpect(jsonPath("$.message").value("Không có"));
    }

    @Test
    void duplicateIsMarkedReplayed() throws Exception {
        when(receiver.receive(eq("payWebhook"), anyMap(), any(), any()))
                .thenReturn(new InboundEventResult(200, "DUPLICATE", null, null, List.of(), List.of(), true));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.data.status").value("DUPLICATE"));
    }
}
