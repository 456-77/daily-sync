package com.dailysync.omofun;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 手动 gRPC 连通性探针（M11，非自动化测试）。
 *
 * <p>不启动 Spring、不连数据库，只验证最难的一层：DNS 探测 → TLS 握手（ALPN h2）→
 * 叶子证书 DER 锁定 → HMAC 签名 → protobuf 编解码 → token 有效。用法：
 * <pre>
 * java -cp "target/classes;target/test-classes;deps" \
 *   com.dailysync.omofun.OmofunProbeMain server.crt auth-tokens.json
 * </pre>
 * 成功输出 "OK count=..."；失败会打印异常链。
 */
public final class OmofunProbeMain {

    private OmofunProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: OmofunProbeMain <server.crt> <auth-tokens.json>");
            System.exit(2);
        }
        ObjectMapper mapper = new ObjectMapper();
        String token = mapper.readTree(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8))
                .path("accessToken").asText();
        if (token.isBlank()) {
            throw new IllegalStateException("auth-tokens.json 中没有 accessToken");
        }
        byte[] pinned = OmofunGrpcClient.loadPinnedCertDer(args[0]);
        System.out.println("pinned cert DER bytes = " + pinned.length);
        try (OmofunGrpcClient client = OmofunGrpcClient.connect(pinned, 15000, 3000)) {
            OmofunProto.ListHistoriesResponse response = client.listHistories(
                    new OmofunProto.ListHistoriesRequest("", 0, 0, 0, 5, true, true), token);
            System.out.println("OK count=" + response.histories().size()
                    + " nextCursor=" + response.nextCursor()
                    + " nextVersion=" + response.nextVersion());
            for (OmofunProto.HistoryItem item : response.histories()) {
                System.out.println("  " + item.contentKey()
                        + " version=" + item.version()
                        + " status=" + item.status()
                        + " title=" + item.title()
                        + " updatedAt=" + item.updatedAtIso());
            }
        }
    }
}
