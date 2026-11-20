package com.apeadmin.chat.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Provider API Key 加密（AES-256-GCM）。
 *
 * <p>密钥从 APE_JWT_SECRET 经 SHA-256 派生（对齐 Python 版 Fernet 的确定性派生思路；
 * Java 侧改用标准 AES-GCM，密文自包含 IV：base64(iv[12] || ct+tag)）。
 * 与 Python 版密文格式互不兼容（各自闭环存储/解密，无跨端交换需求）。
 */
public final class CryptoUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;

    public CryptoUtils(String secret) {
        try {
            this.key = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext);
            if (all.length <= IV_LEN) {
                throw new IllegalArgumentException("密文长度非法");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败", e);
        }
    }

    /** 掩码展示：sk-abc...wxyz / 保留前4后4。 */
    public static String mask(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "****";
        }
        if (plaintext.length() <= 8) {
            return "****";
        }
        return plaintext.substring(0, 4) + "****" + plaintext.substring(plaintext.length() - 4);
    }
}
