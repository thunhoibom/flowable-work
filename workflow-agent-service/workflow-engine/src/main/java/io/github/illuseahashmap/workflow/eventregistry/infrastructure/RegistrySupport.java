package io.github.illuseahashmap.workflow.eventregistry.infrastructure;

import io.github.illuseahashmap.workflow.config.EventRegistryProperties;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.util.StringUtils;

/** Helpers shared by the event model and channel services. */
final class RegistrySupport {

    private RegistrySupport() {
    }

    static void requireEnabled(EventRegistryProperties properties) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Tính năng sự kiện chưa được bật");
        }
    }

    static void requireValid(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, String.join("; ", problems));
        }
    }

    static BusinessException revisionConflict() {
        return new BusinessException(ErrorCode.DRAFT_REVISION_CONFLICT, "Bản nháp đã được người khác sửa. Hãy tải lại.");
    }

    static String actor(CurrentPrincipalProvider principals) {
        CurrentPrincipal principal = principals.current();
        return StringUtils.hasText(principal.username()) ? principal.username() : principal.principalId();
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
