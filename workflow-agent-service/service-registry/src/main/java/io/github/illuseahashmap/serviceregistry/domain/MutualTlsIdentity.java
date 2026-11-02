package io.github.illuseahashmap.serviceregistry.domain;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

/**
 * Client certificate material for one call. The PEM is checked before the request is sent
 * so a bad certificate stays a parameter error.
 */
public record MutualTlsIdentity(PrivateKey privateKey, X509Certificate[] chain) {

    public static MutualTlsIdentity parse(String certificatePem, String privateKeyPem) {
        if (certificatePem == null || certificatePem.isBlank()) {
            throw new InvalidServiceRequestException("Certificate is required");
        }
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new InvalidServiceRequestException("Private key is required");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<?> certificates = factory.generateCertificates(
                    new ByteArrayInputStream(certificatePem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            X509Certificate[] chain = certificates.stream()
                    .filter(X509Certificate.class::isInstance)
                    .map(X509Certificate.class::cast)
                    .toArray(X509Certificate[]::new);
            if (chain.length == 0) {
                throw new InvalidServiceRequestException("Certificate is required");
            }
            return new MutualTlsIdentity(privateKey(privateKeyPem), chain);
        } catch (InvalidServiceRequestException exception) {
            throw exception;
        } catch (RuntimeException | java.security.GeneralSecurityException exception) {
            throw new InvalidServiceRequestException("Certificate is not PEM");
        }
    }

    private static PrivateKey privateKey(String pem) throws java.security.GeneralSecurityException {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(body);
        byte[] pkcs8 = pem.contains("BEGIN RSA PRIVATE KEY") ? wrapPkcs1(decoded) : decoded;
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (java.security.spec.InvalidKeySpecException exception) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }

    /** JDK understands PKCS#8. A PKCS#1 RSA key is wrapped with the rsaEncryption algorithm id. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] algorithm = new byte[] {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        };
        byte[] key = der(0x04, pkcs1);
        byte[] body = new byte[algorithm.length + key.length];
        System.arraycopy(algorithm, 0, body, 0, algorithm.length);
        System.arraycopy(key, 0, body, algorithm.length, key.length);
        return der(0x30, body);
    }

    private static byte[] der(int tag, byte[] value) {
        byte[] length = derLength(value.length);
        byte[] encoded = new byte[1 + length.length + value.length];
        encoded[0] = (byte) tag;
        System.arraycopy(length, 0, encoded, 1, length.length);
        System.arraycopy(value, 0, encoded, 1 + length.length, value.length);
        return encoded;
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[] {(byte) length};
        }
        int size = 1;
        int remaining = length;
        while ((remaining >>= 8) != 0) {
            size++;
        }
        byte[] encoded = new byte[size + 1];
        encoded[0] = (byte) (0x80 | size);
        for (int index = size; index > 0; index--) {
            encoded[index] = (byte) (length & 0xff);
            length >>= 8;
        }
        return encoded;
    }
}
