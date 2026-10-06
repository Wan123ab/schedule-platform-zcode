package com.flowops.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 凭据主密钥启动校验（M-06，docs/05 §8）：
 * 环境变量 FLOWOPS_CRED_MASTER_KEY 缺失则拒绝启动，防明文降级。
 * 本地开发可在 IDE 中设置 FLOWOPS_CRYPTO_STRICT=false 临时放宽（仅 dev，禁止带入生产）。
 */
@Slf4j
@Component
public class CryptoKeyChecker {

    @Value("${flowops.crypto.master-key:}")
    private String masterKey;

    @Value("${flowops.crypto.strict:true}")
    private boolean strict;

    @PostConstruct
    public void check() {
        if (masterKey == null || masterKey.isBlank()) {
            if (strict) {
                throw new IllegalStateException(
                        "环境变量 FLOWOPS_CRED_MASTER_KEY 未设置（M-06：缺失则拒绝启动，防明文降级）");
            }
            log.warn("FLOWOPS_CRED_MASTER_KEY 未设置且 strict=false：凭据加密能力未就绪（仅限本地开发）");
        } else {
            log.info("凭据主密钥已加载（长度={}）", masterKey.length());
        }
    }
}
