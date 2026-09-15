package com.dailysync.service;

import com.dailysync.common.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 附件 blob 的磁盘存储（内容寻址）。
 *
 * <p>布局：{@code <root>/<vaultId>/<sha256 前两位>/<sha256>}。文件名就是内容哈希，
 * 因此天然去重（同仓库内同内容只存一份）、天然无路径穿越（文件名不是用户输入），
 * 且仓库边界落在目录上——删用户/删仓库时 {@code rm -rf <root>/<vaultId>} 即干净收尾，
 * 不需要全局引用计数，也不可能误删别人的数据。
 *
 * <p>权衡：按仓库分层换来了归属可见与删除自包含，代价是**放弃跨仓库去重**
 * （同一张图传到两个仓库各存一份）。个人日记场景下这种情况很少，值得。
 * 也因为没有跨仓库共享，将来要做孤儿 blob 回收时只需按仓库扫，无需全局扫。
 *
 * <p>表里只存元数据（路径、sha256、大小），字节全在这里，两边靠 sha256 关联。
 */
@Slf4j
@Component
public class AttachmentStore {

    /** 哈希前两位做子目录，避免单目录堆几万个文件（部分文件系统会明显变慢） */
    private static final int SHARD_LEN = 2;
    /** 临时文件目录：必须与 blob 在同一文件系统内，ATOMIC_MOVE 才成立 */
    private static final String TMP_DIR = ".tmp";
    private static final int BUF_SIZE = 64 * 1024;

    /** 落盘根目录（构造时归一化成绝对路径，避免受后续工作目录变化影响） */
    private final Path root;

    public AttachmentStore(@Value("${daily-sync.storage.dir:./data/attachments}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        log.info("附件存储目录: {}", root);
    }

    /**
     * 流式写入并按内容哈希落盘。
     *
     * <p>边读边算 SHA-256、边计数，全程不把文件读进内存（容器堆只有约 290M），
     * 超过 {@code maxBytes} 立即中断（{@link TooLargeException}），不会先落盘再回滚。
     * 写完先落临时文件、再 ATOMIC_MOVE 到最终位置，读方永远看不到半截文件。
     *
     * @return 内容的 sha256、字节数，以及是否命中了已有 blob（去重）
     */
    public Stored write(long vaultId, InputStream in, long maxBytes) throws IOException {
        Path tmpDir = root.resolve(TMP_DIR);
        Files.createDirectories(tmpDir);
        Path tmp = Files.createTempFile(tmpDir, "up-", ".part");
        MessageDigest digest = HashUtil.sha256Digest();
        long size = 0;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[BUF_SIZE];
                int n;
                while ((n = in.read(buf)) > 0) {
                    size += n;
                    if (size > maxBytes) {
                        throw new TooLargeException(size);
                    }
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            String sha256 = HashUtil.hex(digest.digest());
            Path target = blobPath(vaultId, sha256);
            if (Files.isRegularFile(target)) {
                // 同内容已在仓库里（可能被别的路径引用）——去重，直接丢弃刚写的临时文件
                return new Stored(sha256, size, true);
            }
            ensureDir(target.getParent());
            moveInto(tmp, target);
            restrict(target, "rw-------");
            return new Stored(sha256, size, false);
        } finally {
            // 成功路径上文件已被 move 走，这里只兜异常与超限的中断路径
            Files.deleteIfExists(tmp);
        }
    }

    /** blob 的磁盘路径。sha256 只来自本类的计算或数据库，不会带用户输入。 */
    public Path blobPath(long vaultId, String sha256) {
        if (sha256 == null || sha256.length() < SHARD_LEN) {
            throw new IllegalArgumentException("非法的内容哈希: " + sha256);
        }
        return root.resolve(String.valueOf(vaultId))
                .resolve(sha256.substring(0, SHARD_LEN))
                .resolve(sha256);
    }

    /** 清掉一个仓库的全部附件文件（管理员删用户时调用；仓库边界在目录上，不会波及其他仓库）。 */
    public void deleteVaultDir(long vaultId) {
        Path dir = root.resolve(String.valueOf(vaultId));
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            // 倒序：先删文件再删目录，否则非空目录删不掉
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("删除附件文件失败: {}", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("清理仓库附件目录失败: {}", dir, e);
        }
    }

    private static void moveInto(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 少数文件系统/跨卷不支持原子移动，退化为普通移动（内容寻址下极端交错也只是重复写同一份内容）
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            // 并发上传同一份内容：目标已就位，等价于去重成功
        }
    }

    private static void ensureDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
        }
    }

    /** 收紧权限（第二层防护：附件只经带 JWT 的接口下发，磁盘权限是兜底）。 */
    private static void restrict(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 开发机没有 POSIX 权限位；容器内是 Linux，会真正生效
            log.debug("设置文件权限失败（忽略）: {} {}", path, e.toString());
        }
    }

    /** 一次写入的结果。 */
    public record Stored(String sha256, long size, boolean deduped) {}

    /** 单文件超限：由 Service 层翻成 413，磁盘层不掺和 HTTP 语义。 */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException(long size) {
            super("附件超过单文件上限（已读 " + size + " 字节）");
        }
    }
}
