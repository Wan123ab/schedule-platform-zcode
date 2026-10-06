package com.flowops.domain.dto.query;

import lombok.Data;

/** 下发期凭据投影（ExecutorDispatchSink 专用；明文只存在于解密后的内存栈）。 */
@Data
public class CredentialDispatchRow {

    /** SSH / USER_PASSWORD / WINRM / TOKEN */
    private String credentialType;

    private String username;

    /** AES-256-GCM 密文（Base64），SecretCryptoService.decrypt 后用完即弃 */
    private String secretEncrypted;
}
