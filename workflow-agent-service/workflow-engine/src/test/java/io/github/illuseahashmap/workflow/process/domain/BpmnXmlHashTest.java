package io.github.illuseahashmap.workflow.process.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BpmnXmlHashTest {

    @Test
    void ignoresLineEndingsAndOuterWhitespace() {
        assertThat(BpmnXmlHash.of("<a>\r\n<b/>\r\n</a>\n"))
                .isEqualTo(BpmnXmlHash.of("  <a>\n<b/>\n</a>"));
    }

    @Test
    void changesWhenContentChanges() {
        assertThat(BpmnXmlHash.of("<a/>")).isNotEqualTo(BpmnXmlHash.of("<b/>"));
    }

    @Test
    void isLowercaseHexOfSha256() {
        assertThat(BpmnXmlHash.of("<a/>")).matches("[0-9a-f]{64}");
    }
}
