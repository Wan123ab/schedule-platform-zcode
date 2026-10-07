package com.flowops.modules.asset.service;

import com.flowops.common.api.FieldError;
import com.flowops.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 算子包存储单测（docs/07 §6.3 的类型白名单/大小上限 + 内容寻址落盘）。
 *
 * <p>用真实文件系统（{@link TempDir}）而不是 mock：本类的核心价值就是"磁盘上到底
 * 写出了什么"，mock 掉 Files 等于把被测对象换成断言不了的替身。</p>
 */
class OperatorFileStorageTest {

    @TempDir
    Path tempDir;

    private OperatorFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new OperatorFileStorage(tempDir.toString(), 1);   // 1MB 上限，便于构造超限用例
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    // ── 校验（只校验不落盘）──────────────────────────────────

    @Test
    void 空文件_校验失败() {
        assertThat(storage.validate(file("job.jar", new byte[0])))
                .singleElement().extracting(FieldError::field).isEqualTo("file");
    }

    @Test
    void 扩展名不在白名单_校验失败() {
        assertThat(storage.validate(file("job.exe.bak", "x".getBytes(StandardCharsets.UTF_8))))
                .singleElement().extracting(FieldError::message).asString().contains("类型不允许");
    }

    @Test
    void 超过大小上限_校验失败() {
        assertThat(storage.validate(file("job.jar", new byte[2 * 1024 * 1024])))
                .singleElement().extracting(FieldError::message).asString().contains("大小上限");
    }

    @Test
    void 合法文件_校验通过且不产生任何磁盘写入() throws Exception {
        storage.validate(file("job.jar", "abc".getBytes(StandardCharsets.UTF_8)));

        try (var entries = Files.list(tempDir)) {
            assertThat(entries).isEmpty();      // 校验阶段必须零副作用
        }
    }

    // ── 落盘（内容寻址）──────────────────────────────────────

    @Test
    void 落盘_路径由内容决定且摘要与字节数正确() throws Exception {
        byte[] content = "print('hello')".getBytes(StandardCharsets.UTF_8);

        OperatorFileStorage.StoredFile stored = storage.store("OP-0001", file("job.py", content));

        // 相对路径形如 OP-0001/<sha256>.py
        assertThat(stored.relativePath()).startsWith("OP-0001/").endsWith(".py");
        assertThat(stored.checksum()).hasSize(64).isEqualTo(
                java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(storage.resolve(stored.relativePath()))).isEqualTo(content);
    }

    /** 同一份内容重复上传：磁盘上只有一份文件（内容寻址的天然去重）。 */
    @Test
    void 相同内容重复落盘_复用同一份文件() throws Exception {
        byte[] content = "same".getBytes(StandardCharsets.UTF_8);

        var first = storage.store("OP-0001", file("a.jar", content));
        var second = storage.store("OP-0001", file("b.jar", content));

        assertThat(second.relativePath()).isEqualTo(first.relativePath());
        try (var entries = Files.list(tempDir.resolve("OP-0001"))) {
            assertThat(entries).hasSize(1);     // 没有留下第二份、也没有留下临时文件
        }
    }

    /** 不同算子分目录：避免单目录堆几十万个文件（文件系统与 ls 都会退化）。 */
    @Test
    void 不同算子_分目录存放() {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        var a = storage.store("OP-0001", file("a.jar", content));
        var b = storage.store("OP-0002", file("a.jar", content));

        assertThat(a.relativePath()).startsWith("OP-0001/");
        assertThat(b.relativePath()).startsWith("OP-0002/");
    }

    /**
     * 校验失败时不落盘 —— 否则目录里会出现"来路不明"的包。
     *
     * <p>夹具用 {@code .tar.gz}（末位扩展名 gz 不在白名单）。注意白名单只认<b>末位</b>
     * 扩展名，故 {@code job.zip.exe} 是合法的（exe 在白名单里）—— 这是"按扩展名判定"
     * 的固有性质，一期不引入内容嗅探与病毒扫描（docs/08 §15.3-5）。</p>
     */
    @Test
    void 校验不通过的扩展名_落盘前被拒() throws Exception {
        assertThat(storage.validate(file("job.tar.gz", "x".getBytes(StandardCharsets.UTF_8)))).isNotEmpty();
        try (var entries = Files.list(tempDir)) {
            assertThat(entries).isEmpty();
        }
    }

    /** 落盘失败属于服务端问题（50000），不能伪装成"上传校验失败"（42210）把用户引向改文件。 */
    @Test
    void 落盘失败_报服务端错误而非校验错误() {
        OperatorFileStorage broken = new OperatorFileStorage(tempDir.resolve("nested").toString(), 1);
        // 把根目录位置先占成一个文件，createDirectories 必然失败
        assertThatThrownBy(() -> {
            Files.writeString(tempDir.resolve("nested"), "occupied");
            broken.store("OP-0001", file("job.jar", "x".getBytes(StandardCharsets.UTF_8)));
        }).isInstanceOf(BizException.class)
                .hasMessageContaining("算子包写入失败");
    }

    @Test
    void 无扩展名文件_校验失败() {
        assertThat(storage.validate(file("job", "x".getBytes(StandardCharsets.UTF_8))))
                .singleElement().extracting(FieldError::field).isEqualTo("file");
    }

    @Test
    void 扩展名大小写不敏感() {
        assertThat(storage.validate(file("JOB.JAR", "x".getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
    }

    /** 类型与大小同时不合法时两条都返回（用户一次就能看到全部问题）。 */
    @Test
    void 校验结果_可同时包含多条错误() {
        List<FieldError> errors = storage.validate(new MockMultipartFile(
                "file", "big.rar", "application/octet-stream", new byte[2 * 1024 * 1024]));

        assertThat(errors).hasSize(2);
        assertThat(errors).extracting(FieldError::message).allMatch(m -> m.contains("类型不允许") || m.contains("大小上限"));
    }
}
