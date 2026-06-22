package com.example.orders.auth.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Читает обычный PEM и значение с экранированными переводами строк.
 */
final class PemKeys {

    private PemKeys() {
    }

    /**
     * @param pem закрытый ключ в формате PKCS#8
     * @return закрытый ключ RSA
     */
    static RSAPrivateKey readPrivateKey(String pem) {
        try {
            return (RSAPrivateKey) rsa().generatePrivate(new PKCS8EncodedKeySpec(der(resolvePem(pem))));
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "auth.jwt.private-key (AUTH_JWT_PRIVATE_KEY) is not a valid PKCS#8 RSA PEM private key", e);
        }
    }

    /**
     * @param pem открытый ключ X.509 (SubjectPublicKeyInfo)
     * @return открытый ключ RSA
     */
    static RSAPublicKey readPublicKey(String pem) {
        try {
            return (RSAPublicKey) rsa().generatePublic(new X509EncodedKeySpec(der(resolvePem(pem))));
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "auth.jwt.public-key (AUTH_JWT_PUBLIC_KEY) is not a valid X.509 RSA PEM public key", e);
        }
    }

    private static String resolvePem(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.strip();
        if (trimmed.startsWith("-----BEGIN")) {
            return trimmed;
        }
        if (!trimmed.contains("\n") && !trimmed.contains("\r")) {
            try {
                java.nio.file.Path path = java.nio.file.Path.of(trimmed);
                if (java.nio.file.Files.isRegularFile(path)) {
                    return java.nio.file.Files.readString(path);
                }
            } catch (Exception ignored) {
                // Продолжаем стандартным разбором DER, чтобы сохранить общую ошибку формата.
            }
        }
        return trimmed;
    }

    private static KeyFactory rsa() {
        try {
            return KeyFactory.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA KeyFactory unavailable on this JVM", e);
        }
    }

    private static byte[] der(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replace("\\r", "")
                .replaceAll("-----BEGIN[^-]*-----", "")
                .replaceAll("-----END[^-]*-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
