package com.dailysync.omofun;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OmoFun watchhistory gRPC 客户端（M11，Rust 版 grpc.rs + tls.rs 的 Java 移植）。
 *
 * <p>三个关键点（任何一处退化都会连不上或签名不通过）：
 * <ol>
 *   <li><b>TLS</b>：服务端证书是 X.509 v1 自签泛 CN（无 SAN），标准链校验必然失败。
 *       这里用 JDK 的信任管理器放行链校验，但要求叶子证书 DER 与客户端内置
 *       server.crt <b>逐字节相等</b>——比官方客户端「信任内置证书 + 跳过主机名」更严格。
 *       必须走 ALPN h2：前置 nginx 对无 ALPN 的连接直接回 400。</li>
 *   <li><b>签名</b>：{@code x-sign = hex(HMAC-SHA256(key="DM233_SECRET_KEY_V1",
 *       path ‖ body ‖ timestamp ‖ nonce))}。body 是手写 protobuf 编码的字节，签名与
 *       实际发送内容同源（见 OmofunProto）。</li>
 *   <li><b>端点</b>：四个域名是 XOR+base64 混淆的；按顺序做 TCP 探测，取第一个可达的
 *       (域名, IP)，连接时把 SNI 固定为域名。</li>
 * </ol>
 */
public final class OmofunGrpcClient implements AutoCloseable {

    private static final int PORT = 9091;
    private static final String HMAC_KEY = "DM233_SECRET_KEY_V1";
    private static final String CLIENT_NAME = "omofun";
    /** 与客户端 asar 内 package.json 一致 */
    private static final String CLIENT_VERSION = "2.0.1";

    private static final String PATH_LIST = "/watchhistory.v1.WatchHistoryService/ListHistories";
    private static final String PATH_GET = "/watchhistory.v1.WatchHistoryService/GetHistory";
    private static final String PATH_RECORD = "/watchhistory.v1.WatchHistoryService/RecordProgress";

    /** 客户端内置密文域名（XOR key "omofun" + base64），与 Rust 版 ENCRYPTED_DOMAINS 一致。 */
    private static final String[] ENCRYPTED_DOMAINS = {
            "Gx8OCAZACAwZDgYNAQAFSBYN",
            "DQwMDRAKQQoOEB0dDAMCDFsNDA==",
            "Gx8OCAZAXV5cAhhADAIC",
            "DQwMDRAKQV9cVREDQQ4ACw=="
    };

    private static final Metadata.Key<String> KEY_CLIENT_NAME =
            Metadata.Key.of("x-client-name", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_CLIENT_VERSION =
            Metadata.Key.of("x-client-version", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_PLATFORM =
            Metadata.Key.of("x-platform", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_ARCH =
            Metadata.Key.of("x-arch", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_SIGN =
            Metadata.Key.of("x-sign", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_TIMESTAMP =
            Metadata.Key.of("x-timestamp", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> KEY_NONCE =
            Metadata.Key.of("x-nonce", Metadata.ASCII_STRING_MARSHALLER);

    /** 字节原样透传的 marshaller：响应解码在 OmofunProto 里做，签名也只认这份字节。 */
    private static final MethodDescriptor.Marshaller<byte[]> BYTE_MARSHALLER =
            new MethodDescriptor.Marshaller<>() {
                @Override
                public InputStream stream(byte[] value) {
                    return new ByteArrayInputStream(value);
                }

                @Override
                public byte[] parse(InputStream stream) {
                    try {
                        return stream.readAllBytes();
                    } catch (IOException e) {
                        throw new IllegalStateException("读取 gRPC 响应失败", e);
                    }
                }
            };

    private final ManagedChannel channel;
    private final long requestTimeoutMs;
    private final Map<String, MethodDescriptor<byte[], byte[]>> descriptors = new HashMap<>();

    private OmofunGrpcClient(ManagedChannel channel, long requestTimeoutMs) {
        this.channel = channel;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /**
     * 连接：端点探测 → 锁定叶子证书的 TLS 通道（JDK SSLProvider，天然支持 ALPN h2）。
     *
     * @param pinnedLeafDer  客户端内置 server.crt 的第一个 CERTIFICATE 块 DER
     * @param requestTimeout 单次 RPC 超时（毫秒）
     * @param probeTimeout   端点 TCP 探测超时（毫秒）
     */
    public static OmofunGrpcClient connect(byte[] pinnedLeafDer, long requestTimeout,
                                           long probeTimeout) {
        Endpoint endpoint = discover(probeTimeout);
        try {
            SslContext sslContext = GrpcSslContexts.configure(
                    SslContextBuilder.forClient().trustManager(new PinnedLeafTrustManager(pinnedLeafDer)),
                    SslProvider.JDK).build();
            ManagedChannel channel = NettyChannelBuilder
                    .forAddress(new InetSocketAddress(endpoint.address(), PORT))
                    // SNI 必须是域名而不是 IP：服务端按域名分流；证书校验已由 DER 锁定接管
                    .overrideAuthority(endpoint.domain())
                    .sslContext(sslContext)
                    .negotiationType(NegotiationType.TLS)
                    .build();
            return new OmofunGrpcClient(channel, requestTimeout);
        } catch (Exception e) {
            throw new OmofunException("构建 TLS 通道失败: " + e.getMessage(), e);
        }
    }

    public OmofunProto.ListHistoriesResponse listHistories(OmofunProto.ListHistoriesRequest request,
                                                           String token) {
        byte[] body = OmofunProto.encodeListHistoriesRequest(request);
        try {
            return OmofunProto.decodeListHistoriesResponse(unary(PATH_LIST, body, token));
        } catch (IOException e) {
            throw new OmofunException("解析 ListHistories 响应失败: " + e.getMessage(), e);
        }
    }

    public OmofunProto.GetHistoryResponse getHistory(OmofunProto.GetHistoryRequest request,
                                                     String token) {
        byte[] body = OmofunProto.encodeGetHistoryRequest(request);
        try {
            return OmofunProto.decodeGetHistoryResponse(unary(PATH_GET, body, token));
        } catch (IOException e) {
            throw new OmofunException("解析 GetHistory 响应失败: " + e.getMessage(), e);
        }
    }

    public OmofunProto.RecordProgressResponse recordProgress(
            OmofunProto.RecordProgressRequest request, String token) {
        byte[] body = OmofunProto.encodeRecordProgressRequest(request);
        try {
            return OmofunProto.decodeRecordProgressResponse(unary(PATH_RECORD, body, token));
        } catch (IOException e) {
            throw new OmofunException("解析 RecordProgress 响应失败: " + e.getMessage(), e);
        }
    }

    /** 是否版本冲突：ABORTED，或 details 含 conflict（与官方客户端判定一致）。 */
    public static boolean isConflict(OmofunException e) {
        return e.getCode() == io.grpc.Status.Code.ABORTED
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("conflict"));
    }

    /** 是否 token 失效。 */
    public static boolean isUnauthenticated(OmofunException e) {
        return e.getCode() == io.grpc.Status.Code.UNAUTHENTICATED;
    }

    // ---------- 内部 ----------

    private byte[] unary(String path, byte[] body, String token) {
        Metadata headers = new Metadata();
        headers.put(KEY_CLIENT_NAME, CLIENT_NAME);
        headers.put(KEY_CLIENT_VERSION, CLIENT_VERSION);
        headers.put(KEY_PLATFORM, "web");
        headers.put(KEY_ARCH, "x64");
        if (token != null && !token.isEmpty()) {
            headers.put(KEY_AUTHORIZATION, "Bearer " + token);
        }
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String timestampText = Long.toString(timestamp);
        headers.put(KEY_SIGN, sign(path, body, timestampText, nonce));
        headers.put(KEY_TIMESTAMP, timestampText);
        headers.put(KEY_NONCE, nonce);

        // 每次调用单独挂签名头：签名依赖本次请求体与时间戳，不能复用 interceptor
        Channel signed = ClientInterceptors.intercept(channel,
                MetadataUtils.newAttachHeadersInterceptor(headers));
        try {
            return ClientCalls.blockingUnaryCall(signed, descriptor(path),
                    CallOptions.DEFAULT.withDeadlineAfter(requestTimeoutMs, TimeUnit.MILLISECONDS),
                    body);
        } catch (StatusRuntimeException e) {
            String detail = e.getStatus().getDescription();
            throw new OmofunException(e.getStatus().getCode(),
                    "gRPC " + path + " 失败（" + e.getStatus().getCode() + "）: "
                            + (detail == null ? e.getMessage() : detail), e);
        }
    }

    private MethodDescriptor<byte[], byte[]> descriptor(String path) {
        return descriptors.computeIfAbsent(path, p -> MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(p.startsWith("/") ? p.substring(1) : p)
                .setRequestMarshaller(BYTE_MARSHALLER)
                .setResponseMarshaller(BYTE_MARSHALLER)
                .build());
    }

    private static String sign(String path, byte[] body, String timestamp, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(path.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    private record Endpoint(String domain, InetAddress address) {
    }

    private static Endpoint discover(long probeTimeoutMs) {
        String lastError = "无域名可试";
        for (String encrypted : ENCRYPTED_DOMAINS) {
            String domain = decryptDomain(encrypted);
            if (domain.isEmpty()) {
                continue;
            }
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(domain);
            } catch (UnknownHostException e) {
                lastError = domain + " 无 DNS 解析";
                continue;
            }
            for (InetAddress address : addresses) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(address, PORT), (int) probeTimeoutMs);
                    return new Endpoint(domain, address);
                } catch (IOException e) {
                    lastError = address + " TCP 失败: " + e.getMessage();
                }
            }
        }
        throw new OmofunException("所有 gRPC 端点均不可达（" + lastError + "）");
    }

    private static String decryptDomain(String encoded) {
        byte[] key = "omofun".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = Base64.getDecoder().decode(encoded);
        byte[] plain = new byte[encrypted.length];
        for (int i = 0; i < encrypted.length; i++) {
            plain[i] = (byte) (encrypted[i] ^ key[i % key.length]);
        }
        return new String(plain, StandardCharsets.UTF_8).trim();
    }

    /** 从 PEM 文件读取第一个 CERTIFICATE 块的 DER（客户端内置 server.crt）。 */
    public static byte[] loadPinnedCertDer(String pemPath) {
        String pem;
        try {
            pem = Files.readString(Path.of(pemPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OmofunException("读取证书失败: " + pemPath, e);
        }
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";
        int start = pem.indexOf(begin);
        if (start < 0) {
            throw new OmofunException("证书文件中没有 CERTIFICATE 块: " + pemPath);
        }
        start += begin.length();
        int stop = pem.indexOf(end, start);
        if (stop < 0) {
            throw new OmofunException("CERTIFICATE 块未闭合: " + pemPath);
        }
        String body = pem.substring(start, stop).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new OmofunException("证书 base64 解码失败: " + pemPath, e);
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 链校验放行、叶子证书 DER 精确比对。
     *
     * <p>为什么不用 trustManager(内置证书) 走标准链校验：服务端证书是 X.509 v1，
     * JDK 能解析但没有任何扩展，链校验/主机名校验都过不了；官方客户端的做法就是
     * 信任内置证书 + 跳过主机名。这里保留「信任内置证书」但把匹配做成逐字节相等。
     */
    private static final class PinnedLeafTrustManager implements X509TrustManager {

        private final byte[] pinned;

        private PinnedLeafTrustManager(byte[] pinned) {
            this.pinned = pinned;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("服务端未提供证书");
            }
            byte[] leaf;
            try {
                leaf = chain[0].getEncoded();
            } catch (CertificateEncodingException e) {
                throw new CertificateException("读取服务端证书失败", e);
            }
            if (!MessageDigest.isEqual(leaf, pinned)) {
                throw new CertificateException("服务端证书与锁定的 server.crt 不匹配（疑似中间人）");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
