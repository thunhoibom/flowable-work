package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterContractTest {

    @Test
    void acceptsSharedNameAndRejectsDuplicatePathAndJsonField() {
        assertDoesNotThrow(() -> ParameterContracts.check(
                List.of(new InputParameter("customerId", ParameterType.STRING, true, ParameterLocation.PATH)),
                List.of(new OutputParameter("customerId", ParameterType.STRING, "customer.id"))));

        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(
                        new InputParameter("customerId", ParameterType.STRING, true, ParameterLocation.PATH),
                        new InputParameter("customerId", ParameterType.STRING, true, ParameterLocation.QUERY)),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(new InputParameter("order id", ParameterType.STRING, true, ParameterLocation.QUERY)),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(new InputParameter("orderId", ParameterType.STRING, false, ParameterLocation.PATH)),
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(),
                List.of(new OutputParameter("id", ParameterType.STRING, "bad\nfield"))));
        assertThrows(IllegalArgumentException.class, () -> ParameterContracts.check(
                List.of(),
                List.of(new OutputParameter("id", ParameterType.STRING, ""))));
    }
}
