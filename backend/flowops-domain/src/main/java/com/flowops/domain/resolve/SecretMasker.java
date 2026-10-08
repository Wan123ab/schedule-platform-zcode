package com.flowops.domain.resolve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 敏感值脱敏器（M-07 / PRD §13.3-2 "全链路脱敏"）。
 *
 * <p><b>为什么需要一个"按值替换"的脱敏器，而 {@link VariableChainResolver} 的
 * 快照脱敏不够</b>：解析器的脱敏发生在<b>参数结构</b>上（把某个 key 的值写成
 * {@code ***}），但真正出网的两条通道是"命令回显"与"日志行"—— 一份包含敏感参数
 * 的启动命令（{@code --password hunter2}）与一条把密钥打出来的日志
 * （{@code token=abc123}）都不再是结构化参数，只能按<b>值</b>做替换。</p>
 *
 * <p><b>为什么长度阈值是 4</b>：按值替换的失败模式不是"漏脱敏"而是"过度脱敏"——
 * 若把一个 1~2 字符的敏感值也替换掉（例如密码 {@code "1"}、token {@code "ab"}），
 * 日志里的每个数字/字母都会被换成 {@code ***}，输出彻底不可读、排障价值归零。
 * 4 字符以下的"密码"本身也不构成秘密。这是 M-07 在一期的一个显式取舍边界，
 * 阈值是常量而不是散落的魔法数字，便于复核与调整。</p>
 *
 * <p><b>为什么按长度倒序匹配</b>：两个敏感值互为前缀时（如 {@code abc123} 与
 * {@code abc123456}），先替换短的会把长的切碎（{@code ***456}），
 * 剩下的碎片就漏出去了。倒序后长值先被整体替换，短值再匹配时已无残留。</p>
 */
public final class SecretMasker {

    /** 脱敏占位符（与 {@link VariableChainResolver#MASKED_VALUE} 同一口径）。 */
    public static final String MASK = VariableChainResolver.MASKED_VALUE;

    /** 短于此长度的值不做按值替换（理由见类注释）。 */
    public static final int MIN_SECRET_LENGTH = 4;

    private static final SecretMasker EMPTY = new SecretMasker(List.of());

    private final List<String> secrets;

    private SecretMasker(List<String> secrets) {
        this.secrets = secrets;
    }

    /**
     * 由原始敏感值集合构建。
     *
     * <p>null / 空白 / 过短的值会被丢弃：调用方是从"参数模板的 sensitive 标记"
     * 与"环境变量的 secret 标记"两处收集值的，"这个敏感参数这次没填值"是常态，
     * 不能因为收集到一个空串就把整行日志打成 {@code ***}。</p>
     */
    public static SecretMasker of(Collection<String> rawSecrets) {
        if (rawSecrets == null || rawSecrets.isEmpty()) {
            return EMPTY;
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String raw : rawSecrets) {
            if (raw != null && !raw.isBlank() && raw.length() >= MIN_SECRET_LENGTH) {
                distinct.add(raw);
            }
        }
        if (distinct.isEmpty()) {
            return EMPTY;
        }
        List<String> ordered = new ArrayList<>(distinct);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        return new SecretMasker(List.copyOf(ordered));
    }

    public static SecretMasker none() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return secrets.isEmpty();
    }

    /** 被纳管的敏感值个数（只用于断言与日志，不暴露值本身）。 */
    public int size() {
        return secrets.size();
    }

    /** 把文本里出现的敏感值全部替换为 {@code ***}；null / 空串原样返回。 */
    public String mask(String text) {
        if (text == null || text.isEmpty() || secrets.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String secret : secrets) {
            if (masked.contains(secret)) {
                masked = masked.replace(secret, MASK);
            }
        }
        return masked;
    }
}
