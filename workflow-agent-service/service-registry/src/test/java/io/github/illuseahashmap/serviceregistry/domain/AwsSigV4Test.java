package io.github.illuseahashmap.serviceregistry.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AwsSigV4Test {

    @Test
    void matchesThePublishedIamGetExample() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
        AwsSigV4.sign(
                HttpMethod.GET,
                "https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08",
                headers,
                new byte[0],
                "AKIDEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "us-east-1",
                "iam",
                Instant.parse("2015-08-30T12:36:00Z"));
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, "
                        + "SignedHeaders=content-type;host;x-amz-date, "
                        + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
                headers.get("Authorization"));
    }
}
