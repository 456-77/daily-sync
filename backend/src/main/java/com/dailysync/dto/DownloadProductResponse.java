package com.dailysync.dto;

import java.util.List;

/**
 * 下载中心的一个软件分类（M12）：产品元数据 + 该产品全部可分发安装包。
 *
 * <p>门户主页只需要 Quick Note，继续用扁平的 {@code /api/v1/downloads}；
 * 本结构只服务 {@code /api/v1/downloads/catalog}（全部安装包页），把「软件」
 * 维度显式化，避免其他软件的包混进 Quick Note 的列表与 latest 判断。
 *
 * @param id          软件目录名（{@code downloads/<id>/<channel>/}，仅小写字母/数字/连字符）
 * @param name        展示名（product.json 覆盖，缺省由目录名生成）
 * @param description 一句话描述（product.json 可选）
 * @param website     官网地址（product.json 可选，仅接受 http/https）
 * @param items       该产品下全部安装包（稳定版在前、版本降序）
 */
public record DownloadProductResponse(String id, String name, String description, String website,
                                      List<DownloadItemResponse> items) {
}
