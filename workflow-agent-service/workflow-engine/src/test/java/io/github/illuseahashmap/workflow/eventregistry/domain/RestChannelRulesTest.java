package io.github.illuseahashmap.workflow.eventregistry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RestChannelRulesTest {

    static RestChannelDefinition channel(KeyDetectionMode mode, String value, List<String> allowed,
                                         List<String> users, List<String> groups) {
        return new RestChannelDefinition("payWebhook", "Webhook", null, mode, value, allowed, users, groups);
    }

    @Test
    void acceptsAValidChannel() {
        assertThat(RestChannelRules.validate(channel(KeyDetectionMode.FIXED, "paymentReceived", null,
                List.of("erp-client"), List.of()))).isEmpty();
        assertThat(RestChannelRules.validate(channel(KeyDetectionMode.JSON_POINTER, "/meta/type",
                List.of("paymentReceived", "refundIssued"), List.of(), List.of("WF_ADMIN")))).isEmpty();
    }

    @Test
    void fixedDetectionAllowsOnlyItsOwnEvent() {
        RestChannelDefinition fixed = channel(KeyDetectionMode.FIXED, "paymentReceived",
                List.of("other"), List.of("u"), List.of());

        assertThat(fixed.allowedEventKeys()).containsExactly("paymentReceived");
    }

    @Test
    void reportsEveryProblem() {
        assertThat(RestChannelRules.validate(new RestChannelDefinition("1bad", " ", null,
                KeyDetectionMode.JSON_FIELD, "not a field", List.of(), List.of(), List.of())))
                .anyMatch(message -> message.contains("Mã kênh"))
                .anyMatch(message -> message.contains("Tên kênh"))
                .anyMatch(message -> message.contains("Trường JSON"))
                .anyMatch(message -> message.contains("ít nhất một sự kiện"))
                .anyMatch(message -> message.contains("người hoặc vai trò"));
        assertThat(RestChannelRules.validate(channel(KeyDetectionMode.JSON_POINTER, "meta/type",
                List.of("ok", "9bad"), List.of("u"), List.of())))
                .anyMatch(message -> message.contains("/"))
                .anyMatch(message -> message.contains("9bad"));
        assertThat(RestChannelRules.validate(channel(null, null, List.of("ok"), List.of("u"), List.of())))
                .anyMatch(message -> message.contains("nhận diện"));
    }

    @Test
    void principalsAreBounded() {
        assertThat(RestChannelRules.validate(channel(KeyDetectionMode.FIXED, "ok", null,
                IntStream.range(0, 51).mapToObj(index -> "u" + index).toList(), List.of())))
                .anyMatch(message -> message.contains("50"));
        assertThat(RestChannelRules.validate(channel(KeyDetectionMode.FIXED, "ok", null,
                List.of(" ", "x".repeat(129)), List.of())))
                .anyMatch(message -> message.contains("trống"))
                .anyMatch(message -> message.contains("128"));
    }
}
