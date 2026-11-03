package com.dsmnh.mobile.modserver.node;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** 握手签名：规范串 HORIZON/ts/nonce，HMAC-SHA256，base64url 无填充（契约 §4.1）。 */
public final class NodeAuth {
    public static final long CLOCK_SKEW_SEC = 600;
    private static final SecureRandom RANDOM = new SecureRandom();

    private NodeAuth() {
    }

    public static String sign(String token, long ts, String nonce) {
        try {
            String canonical = "HORIZON\n" + ts + "\n" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }

    public static String kidOf(String token) {
        return token.substring(0, Math.min(8, token.length()));
    }

    public static String randomSid() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
