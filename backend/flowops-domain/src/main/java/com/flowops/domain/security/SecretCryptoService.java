package com.flowops.domain.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加密服务（docs/03 §4.1 / M-06）：AES-256-GCM。
 *
 * <p><b>密文格式</b>：Base64( 12B 随机 IV ‖ 16B GCM Tag ‖ 密文体 )——IV 每次加密随机生成，
 * 同一明文两次加密产生不同密文（GCM 语义要求，也天然防重放比对）。</p>
 *
 * <p><b>主密钥管理（M-06）</b>：环境变量 FLOWOPS_CRED_MASTER_KEY（缺失则进程拒绝启动，
 * 见两侧的 CryptoKeyChecker）；密钥派生用 SHA-256 将任意长度口令规整为 256 位。
 * 解密仅在执行下发时发生且只在内存（docs/03 §4.1），明文永不落库、永不返回接口。</p>
 */
@Component
public class SecretCryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public SecretCryptoService(@Value("${flowops.crypto.master-key:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            // 交给两侧 CryptoKeyChecker 决定拒绝启动或告警放行；此处构造弱密钥防 NPE（加密功能不可用）
            this.keySpec = deriveKey("UNCONFIGURED");
            return;
        }
        this.keySpec = deriveKey(masterKey);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(IV_BYTES + encrypted.length);
            buffer.put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    public String decrypt(String ciphertextBase64) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertextBase64));
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 密文被篡改 / 主密钥轮换不一致 → 解密失败必须显式暴露，绝不静默返回垃圾
            throw new IllegalStateException("凭据解密失败（密文损坏或主密钥不匹配）", e);
        }
    }

    private static SecretKeySpec deriveKey(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
