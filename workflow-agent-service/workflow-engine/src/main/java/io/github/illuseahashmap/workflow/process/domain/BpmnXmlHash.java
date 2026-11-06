package io.github.illuseahashmap.workflow.process.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content hash of a BPMN document, stable across line-ending differences. */
public final class BpmnXmlHash {

    private BpmnXmlHash() {
    }

    public static String of(String bpmnXml) {
        String normalized = bpmnXml.replace("\r\n", "\n").replace('\r', '\n').strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
