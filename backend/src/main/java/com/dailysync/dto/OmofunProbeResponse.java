package com.dailysync.dto;

/** gRPC 连通性探测结果（对应 Rust 版 CLI 的 probe）。 */
public record OmofunProbeResponse(int count, long nextCursor) {
}
