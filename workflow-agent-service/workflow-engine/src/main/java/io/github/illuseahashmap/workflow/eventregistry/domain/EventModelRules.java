package io.github.illuseahashmap.workflow.eventregistry.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Event model rules of spec 2026-11-09-event-registry-d1-design (R-B3, R-B4, R-E2, R-E3). */
public final class EventModelRules {

    public static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    public static final Pattern FIELD = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    public static final int MAX_FIELDS = 64;
    public static final int MAX_CORRELATIONS = 3;

    private EventModelRules() {
    }

    public static List<String> validate(EventModelDefinition model) {
        List<String> problems = new ArrayList<>();
        if (model.key() == null || !KEY.matcher(model.key()).matches()) {
            problems.add("Mã sự kiện phải khớp " + KEY.pattern());
        }
        if (model.name() == null || model.name().isBlank()) {
            problems.add("Tên sự kiện không được để trống");
        }
        if (model.fields().size() > MAX_FIELDS) {
            problems.add("Sự kiện có tối đa " + MAX_FIELDS + " trường");
        }
        Set<String> seen = new HashSet<>();
        int correlations = 0;
        int fullPayloads = 0;
        for (EventField field : model.fields()) {
            String name = field.name() == null ? "" : field.name();
            if (!FIELD.matcher(name).matches()) {
                problems.add("Tên trường không hợp lệ: " + (name.isEmpty() ? "(trống)" : name));
            } else if (!seen.add(name)) {
                problems.add("Tên trường bị trùng: " + name);
            }
            if (field.type() == null) {
                problems.add("Trường " + name + " chưa có kiểu");
                continue;
            }
            if (field.correlation()) {
                correlations++;
                if (!field.type().correlatable()) {
                    problems.add("Trường tương quan " + name + " chỉ được có kiểu string, integer, long");
                }
            }
            if (field.header() && field.type() == EventFieldType.JSON) {
                problems.add("Trường " + name + " lấy từ header không thể có kiểu json");
            }
            if (field.fullPayload()) {
                fullPayloads++;
                if (field.type() != EventFieldType.JSON) {
                    problems.add("Trường " + name + " nhận toàn bộ nội dung nên phải có kiểu json");
                }
                if (field.correlation()) {
                    problems.add("Trường " + name + " không thể vừa nhận toàn bộ nội dung vừa là tương quan");
                }
                if (field.header()) {
                    problems.add("Trường " + name + " không thể vừa nhận toàn bộ nội dung vừa lấy từ header");
                }
            }
        }
        if (correlations == 0) {
            problems.add("Sự kiện cần ít nhất một trường tương quan");
        }
        if (correlations > MAX_CORRELATIONS) {
            problems.add("Sự kiện có tối đa " + MAX_CORRELATIONS + " trường tương quan");
        }
        if (fullPayloads > 1) {
            problems.add("Chỉ một trường được nhận toàn bộ nội dung");
        }
        return problems;
    }

    /** Changes that would break running instances or deployed processes. Empty means compatible. */
    public static List<String> incompatibilities(List<EventField> published, List<EventField> next) {
        Map<String, EventField> after = new LinkedHashMap<>();
        next.forEach(field -> after.put(field.name(), field));
        Set<String> before = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (EventField old : published) {
            before.add(old.name());
            EventField now = after.get(old.name());
            if (now == null) {
                problems.add("Xóa trường " + old.name());
                continue;
            }
            if (now.type() != old.type()) {
                problems.add("Đổi kiểu trường " + old.name() + " từ " + flowableName(old.type())
                        + " sang " + flowableName(now.type()));
            }
            if (old.correlation() && !now.correlation()) {
                problems.add("Bỏ tương quan của trường " + old.name());
            }
            if (!old.correlation() && now.correlation()) {
                problems.add("Đặt trường " + old.name() + " thành tương quan");
            }
            if (old.header() != now.header()) {
                problems.add("Đổi nguồn header của trường " + old.name());
            }
            if (old.fullPayload() != now.fullPayload()) {
                problems.add("Đổi cờ nhận toàn bộ nội dung của trường " + old.name());
            }
        }
        after.values().stream()
                .filter(field -> !before.contains(field.name()) && field.correlation())
                .forEach(field -> problems.add("Thêm trường tương quan " + field.name()));
        return problems;
    }

    private static String flowableName(EventFieldType type) {
        return type == null ? "(trống)" : type.flowableName();
    }
}
