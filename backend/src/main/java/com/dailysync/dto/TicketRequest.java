package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
/** 门户免登录票据兑换请求（M9）。 */
public class TicketRequest {
    @NotBlank
    @Size(max = 128)
    private String ticket;
}
