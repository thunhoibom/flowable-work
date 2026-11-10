package io.github.illuseahashmap.workflow.eventregistry.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Saves an event model draft. {@code revision = 0} creates it. */
public record SaveEventModelRequest(
        @PositiveOrZero long revision,
        @NotBlank @Size(max = 256) String name,
        @Size(max = 1024) String description,
        @NotNull List<EventFieldView> fields) {
}
