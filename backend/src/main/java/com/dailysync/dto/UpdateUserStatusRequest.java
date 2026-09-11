package com.dailysync.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 管理员启用/禁用用户请求。 */
@Data
public class UpdateUserStatusRequest {

    /** 1=启用，0=禁用 */
    @NotNull
    @Min(value = 0, message = "状态只能是 0（禁用）或 1（启用）")
    @Max(value = 1, message = "状态只能是 0（禁用）或 1（启用）")
    private Integer status;
}
