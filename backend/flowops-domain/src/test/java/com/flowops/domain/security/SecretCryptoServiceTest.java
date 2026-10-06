package com.flowops.domain.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 凭据加密单测（M-06 / docs/03 §4.1）：GCM 往返、防篡改、同明文异密文。 */
class SecretCryptoServiceTest {

    @Test
    void 加密解密往返() {
        var crypto = new SecretCryptoService("unit-test-master-key");
        String secret = "p@ssw0rd-中文-123";

        assertThat(crypto.decrypt(crypto.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void 同一明文两次加密_密文不同_均正确解密() {
        var crypto = new SecretCryptoService("unit-test-master-key");
        String c1 = crypto.encrypt("same-secret");
        String c2 = crypto.encrypt("same-secret");

        assertThat(c1).isNotEqualTo(c2);   // GCM 随机 IV：天然防密文比对重放
        assertThat(crypto.decrypt(c1)).isEqualTo("same-secret");
        assertThat(crypto.decrypt(c2)).isEqualTo("same-secret");
    }

    @Test
    void 密文被篡改_解密显式失败() {
        var crypto = new SecretCryptoService("unit-test-master-key");
        String tampered = crypto.encrypt("secret") + "x";

        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("凭据解密失败");
    }

    @Test
    void 主密钥不一致_解密失败() {
        String ciphertext = new SecretCryptoService("key-A").encrypt("secret");

        assertThatThrownBy(() -> new SecretCryptoService("key-B").decrypt(ciphertext))
                .isInstanceOf(IllegalStateException.class);
    }
}
