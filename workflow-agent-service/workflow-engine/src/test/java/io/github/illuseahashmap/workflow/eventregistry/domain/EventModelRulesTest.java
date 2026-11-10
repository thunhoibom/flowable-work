package io.github.illuseahashmap.workflow.eventregistry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventModelRulesTest {

    static EventField field(String name, EventFieldType type) {
        return new EventField(name, type, false, false, false);
    }

    static EventField correlation(String name, EventFieldType type) {
        return new EventField(name, type, true, false, false);
    }

    static EventModelDefinition model(EventField... fields) {
        return new EventModelDefinition("paymentReceived", "Đã nhận thanh toán", null, List.of(fields));
    }

    @Test
    void acceptsAValidModel() {
        assertThat(EventModelRules.validate(model(
                correlation("orderId", EventFieldType.STRING),
                field("amount", EventFieldType.DOUBLE),
                new EventField("raw", EventFieldType.JSON, false, false, true)))).isEmpty();
    }

    @Test
    void reportsEveryStructuralProblem() {
        List<EventField> tooMany = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            tooMany.add(field("f" + index, EventFieldType.STRING));
        }
        assertThat(EventModelRules.validate(new EventModelDefinition("1bad", " ", null, List.of())))
                .anyMatch(message -> message.contains("Mã sự kiện"))
                .anyMatch(message -> message.contains("Tên"))
                .anyMatch(message -> message.contains("tương quan"));
        assertThat(EventModelRules.validate(new EventModelDefinition("ok", "Ok", null, tooMany)))
                .anyMatch(message -> message.contains("64"));
    }

    @Test
    void correlationFieldsAreLimitedInNumberAndType() {
        assertThat(EventModelRules.validate(model(correlation("a", EventFieldType.DOUBLE))))
                .anyMatch(message -> message.contains("a") && message.contains("string, integer, long"));
        assertThat(EventModelRules.validate(model(
                correlation("a", EventFieldType.STRING), correlation("b", EventFieldType.STRING),
                correlation("c", EventFieldType.LONG), correlation("d", EventFieldType.INTEGER))))
                .anyMatch(message -> message.contains("3"));
    }

    @Test
    void fullPayloadAndNamesAreChecked() {
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING),
                new EventField("raw", EventFieldType.STRING, false, false, true))))
                .anyMatch(message -> message.contains("raw") && message.contains("json"));
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING),
                new EventField("a", EventFieldType.JSON, false, false, true),
                new EventField("b", EventFieldType.JSON, false, false, true))))
                .anyMatch(message -> message.contains("một"));
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING),
                new EventField("raw", EventFieldType.JSON, false, true, true))))
                .anyMatch(message -> message.contains("raw") && message.contains("toàn bộ") && message.contains("header"))
                .anyMatch(message -> message.contains("raw") && message.contains("header") && message.contains("json"));
        assertThat(EventModelRules.validate(model(
                correlation("id", EventFieldType.STRING), field("id", EventFieldType.STRING),
                field("9x", EventFieldType.STRING))))
                .anyMatch(message -> message.contains("trùng"))
                .anyMatch(message -> message.contains("9x"));
    }

    @Test
    void onlyAddingPlainFieldsIsCompatible() {
        List<EventField> published = List.of(correlation("orderId", EventFieldType.STRING),
                field("amount", EventFieldType.DOUBLE));

        assertThat(EventModelRules.incompatibilities(published, List.of(
                correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE),
                field("note", EventFieldType.STRING)))).isEmpty();
        assertThat(EventModelRules.incompatibilities(published, List.of(correlation("orderId", EventFieldType.STRING))))
                .containsExactly("Xóa trường amount");
        assertThat(EventModelRules.incompatibilities(published, List.of(
                correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.LONG))))
                .containsExactly("Đổi kiểu trường amount từ double sang long");
        assertThat(EventModelRules.incompatibilities(published, List.of(
                field("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE))))
                .containsExactly("Bỏ tương quan của trường orderId");
        assertThat(EventModelRules.incompatibilities(published, List.of(
                correlation("orderId", EventFieldType.STRING), field("amount", EventFieldType.DOUBLE),
                correlation("customerId", EventFieldType.STRING))))
                .containsExactly("Thêm trường tương quan customerId");
    }
}
