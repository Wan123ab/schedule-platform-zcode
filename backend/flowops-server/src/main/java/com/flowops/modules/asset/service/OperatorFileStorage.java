package com.flowops.modules.asset.service;

import com.flowops.common.api.ErrorCode;
import com.flowops.common.api.FieldError;
import com.flowops.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 算子包文件存储（docs/07 §6.3 的校验项 + {@code file_path} 落盘）。
 *
 * <p><b>校验与落盘拆成两步</b>（{@link #validate} / {@link #store}）：校验项散落在
 * 文件与 meta 两处，若各自"发现即抛"，用户就要按提交次数逐个发现错误。拆开后
 * Service 可以把两处的错误合成一个 42210 一次回填（docs/07 §6.3 的 {@code errors[]}）。</p>
 *
 * <p><b>内容寻址（content-addressed）</b>：文件名取 {@code <root>/<operatorId>/<sha256>.<ext>}，
 * 即"路径由内容决定"。三个直接好处：同一份包上传为多个版本时磁盘只存一份；摘要相同的
 * 上传可直接复用；路径本身可校验（重算摘要即可确认文件没被替换）。</p>
 *
 * <p><b>边写边算摘要</b>：500MB 的包若先完整读一遍算摘要、再读一遍写盘，就是双倍 IO。
 * {@link DigestInputStream} 让一次遍历同时完成校验与落盘。</p>
 */
@Slf4j
@Service
public class OperatorFileStorage {

    /** docs/07 §6.3 的类型白名单（以扩展名判定；一期不引入病毒扫描，见 docs/08 §15.3-5） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jar", "py", "sh", "bat", "exe", "zip");

    private final Path root;
    private final long maxFileSizeBytes;

    public OperatorFileStorage(@Value("${flowops.operator.storage-root:data/operators}") String storageRoot,
                               @Value("${flowops.operator.max-file-size-mb:500}") long maxFileSizeMb) {
        this.root = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxFileSizeBytes = maxFileSizeMb * 1024 * 1024;
    }

    /** 落盘结果：相对路径（入库）+ 摘要 + 字节数。 */
    public record StoredFile(String relativePath, String checksum, long size) {
    }

    /**
     * 只校验不落盘（返回全部问题，而不是抛出第一个）。
     * 字段名用 {@code file}，与 docs/07 §6.3 错误回填的表单字段对齐。
     */
    public List<FieldError> validate(MultipartFile file) {
        List<FieldError> errors = new ArrayList<>();
        if (file == null || file.isEmpty()) {
            errors.add(FieldError.of("file", "上传文件为空"));
            return errors;
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            errors.add(FieldError.of("file", "文件类型不允许，仅支持 " + String.join(" / ", ALLOWED_EXTENSIONS)));
        }
        if (file.getSize() > maxFileSizeBytes) {
            errors.add(FieldError.of("file",
                    "文件超过大小上限 " + (maxFileSizeBytes / 1024 / 1024) + "MB"));
        }
        return errors;
    }

    /**
     * 落盘（调用前应已通过 {@link #validate}）。
     *
     * @param operatorId 算子业务编号，仅用于分目录（避免单目录堆几万个文件）
     */
    public StoredFile store(String operatorId, MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        try {
            Path dir = root.resolve(operatorId);
            Files.createDirectories(dir);

            // 先写临时文件再原子改名：进程在写盘中途挂掉时，目录里不会留下"半份包"，
            // 也就不会出现"文件存在但摘要是错的"这种最难查的状态
            Path temp = Files.createTempFile(dir, "upload-", ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream in = file.getInputStream();
                 DigestInputStream digestIn = new DigestInputStream(in, digest);
                 OutputStream out = Files.newOutputStream(temp)) {
                size = digestIn.transferTo(out);
            }

            String checksum = HexFormat.of().formatHex(digest.digest());
            Path target = dir.resolve(checksum + "." + extension);
            if (Files.exists(target)) {
                // 内容寻址的天然去重：同一份包重复上传直接复用已有文件
                Files.deleteIfExists(temp);
                log.info("算子包已存在，复用磁盘副本 operator={} checksum={}", operatorId, checksum);
            } else {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            }
            log.info("算子包已落盘 operator={} size={} checksum={}", operatorId, size, checksum);
            return new StoredFile(relative(root.relativize(target)), checksum, size);
        } catch (IOException | NoSuchAlgorithmException e) {
            // 落盘失败是服务端问题（50000），不能伪装成"上传校验失败"（那会把用户引向改文件）
            throw new BizException(ErrorCode.INTERNAL_ERROR, "算子包写入失败: " + e.getMessage());
        }
    }

    /** 按相对路径还原绝对路径（试运行与执行下发用）。 */
    public Path resolve(String relativePath) {
        return root.resolve(relativePath).normalize();
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 统一成正斜杠：入库路径要跨平台可读（Windows 写 \、Linux 写 / 会让日志检索失效）。 */
    private String relative(Path path) {
        return path.toString().replace('\\', '/');
    }
}
