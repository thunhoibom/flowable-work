package io.github.illuseahashmap.workflow.serviceregistry;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceCallbackController {

    private final ServiceCallbackService callbacks;

    public ServiceCallbackController(ServiceCallbackService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/service-callbacks/{correlationId}")
    public ResponseEntity<Void> callback(@PathVariable String correlationId, HttpServletRequest request)
            throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        boolean completed = callbacks.complete(correlationId, new String(body, java.nio.charset.StandardCharsets.UTF_8), headers(request), body);
        return completed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Authorization and Cookie stay out of process variables even if an output maps a header. */
    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        java.util.Collections.list(request.getHeaderNames()).forEach(name -> {
            if (name != null
                    && !name.equalsIgnoreCase("authorization")
                    && !name.equalsIgnoreCase("cookie")) {
                headers.put(name, request.getHeader(name));
            }
        });
        return headers;
    }
}
