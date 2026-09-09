package com.dailysync.dto;

import jakarta.validation.constraints.Size;

public record CreateSyncTokenRequest(@Size(max = 64, message = "备注名最长 64 字符") String name) {
}
