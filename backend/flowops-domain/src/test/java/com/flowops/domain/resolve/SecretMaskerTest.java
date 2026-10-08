package com.flowops.domain.resolve;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感值脱敏器单测（M-07）。
 *
 * <p>脱敏的用例必须同时盯<b>两个方向</b>：该打码的打了（否则是泄漏），
 * 不该动的没动（否则日志被毁、排障价值归零）。只测前者会漏掉"过度脱敏"
 * ——那也是一种线上事故，只是方向不同。</p>
 */
class SecretMaskerTest {

    @Test
    void 长值在多处出现时全部替换() {
        SecretMasker masker = SecretMasker.of(List.of("hunter2secret"));

        assertThat(masker.mask("cmd --password hunter2secret --check hunter2secret"))
                .isEqualTo("cmd --password *** --check ***");
    }

    @Test
    void 短于阈值的值不参与替换_避免把日志打成马赛克() {
        SecretMasker masker = SecretMasker.of(List.of("12", "abc"));

        assertThat(masker.isEmpty()).isTrue();
        assertThat(masker.mask("rows=1234 name=abc")).isEqualTo("rows=1234 name=abc");
    }

    /**
     * 互为前缀的两个敏感值：先替换短的会把长的切碎（{@code ***456}），
     * 剩下的碎片就漏出去了 —— 这条用例就是"按长度倒序"的存在理由。
     */
    @Test
    void 互为前缀的敏感值_长的先替换否则会残留碎片() {
        SecretMasker masker = SecretMasker.of(List.of("tok-abcd1234", "tok-abcd123456"));

        assertThat(masker.mask("Authorization: tok-abcd123456"))
                .isEqualTo("Authorization: ***")
                .doesNotContain("456");
    }

    @Test
    void 空值与null被丢弃_不会把整行变成占位符() {
        SecretMasker masker = SecretMasker.of(Arrays.asList(null, "", "   ", "real-secret"));

        assertThat(masker.size()).isEqualTo(1);
        assertThat(masker.mask("a real-secret b")).isEqualTo("a *** b");
    }

    @Test
    void 重复值去重_次数不影响结果() {
        assertThat(SecretMasker.of(List.of("dup-secret", "dup-secret")).size()).isEqualTo(1);
    }

    @Test
    void 空集合与null集合_原样返回文本() {
        assertThat(SecretMasker.of(null).mask("nothing to hide")).isEqualTo("nothing to hide");
        assertThat(SecretMasker.of(List.of()).mask("nothing to hide")).isEqualTo("nothing to hide");
    }

    @Test
    void null与空串输入原样返回() {
        SecretMasker masker = SecretMasker.of(List.of("secret-value"));

        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    void 未命中时文本保持不变() {
        SecretMasker masker = SecretMasker.of(List.of("secret-value"));

        assertThat(masker.mask("no secrets here")).isEqualTo("no secrets here");
    }

    @Test
    void 占位符与解析器快照口径一致() {
        assertThat(SecretMasker.MASK).isEqualTo(VariableChainResolver.MASKED_VALUE);
    }
}
