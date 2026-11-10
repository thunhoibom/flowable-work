package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.ArrayList;
import java.util.List;

/** REST channel rules of spec 2026-11-09-event-registry-d1-design. */
public final class RestChannelRules {

    public static final int MAX_PRINCIPALS = 50;
    public static final int MAX_PRINCIPAL_LENGTH = 128;

    private RestChannelRules() {
    }

    public static List<String> validate(RestChannelDefinition channel) {
        List<String> problems = new ArrayList<>();
        if (channel.key() == null || !EventModelRules.KEY.matcher(channel.key()).matches()) {
            problems.add("Mã kênh phải khớp " + EventModelRules.KEY.pattern());
        }
        if (channel.name() == null || channel.name().isBlank()) {
            problems.add("Tên kênh không được để trống");
        }
        detection(channel, problems);
        if (channel.allowedEventKeys().isEmpty()) {
            problems.add("Kênh cần nhận ít nhất một sự kiện");
        }
        channel.allowedEventKeys().stream()
                .filter(key -> !EventModelRules.KEY.matcher(key).matches())
                .forEach(key -> problems.add("Mã sự kiện không hợp lệ: " + key));
        if (channel.userIds().isEmpty() && channel.groupIds().isEmpty()) {
            problems.add("Kênh cần ít nhất một người hoặc vai trò được gửi");
        }
        principals(channel.userIds(), "người", problems);
        principals(channel.groupIds(), "vai trò", problems);
        return problems;
    }

    private static void detection(RestChannelDefinition channel, List<String> problems) {
        String value = channel.detectionValue() == null ? "" : channel.detectionValue();
        if (channel.detection() == null) {
            problems.add("Chưa chọn cách nhận diện sự kiện");
            return;
        }
        if (value.isBlank()) {
            problems.add("Chưa nhập giá trị nhận diện sự kiện");
            return;
        }
        switch (channel.detection()) {
            case FIXED -> {
                if (!EventModelRules.KEY.matcher(value).matches()) {
                    problems.add("Mã sự kiện cố định không hợp lệ: " + value);
                }
            }
            case JSON_FIELD -> {
                if (!EventModelRules.FIELD.matcher(value).matches()) {
                    problems.add("Trường JSON không hợp lệ: " + value);
                }
            }
            case JSON_POINTER -> {
                if (!value.startsWith("/")) {
                    problems.add("JSON Pointer phải bắt đầu bằng /");
                }
            }
        }
    }

    private static void principals(List<String> values, String label, List<String> problems) {
        if (values.size() > MAX_PRINCIPALS) {
            problems.add("Tối đa " + MAX_PRINCIPALS + " " + label + " được gửi");
        }
        for (String value : values) {
            if (value.isBlank()) {
                problems.add("Mã " + label + " được gửi không được để trống");
            } else if (value.length() > MAX_PRINCIPAL_LENGTH) {
                problems.add("Mã " + label + " được gửi dài quá " + MAX_PRINCIPAL_LENGTH + " ký tự");
            }
        }
    }
}
