package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * OmoFun 每次存档（poll）的运行记录（omofun_poll_runs）。
 *
 * <p>保活/恢复不记这张表——它们的成败在任务日志里；原 Rust 版也只记 poll，
 * 网页总览的「最近运行」表格就是这里的数据。
 */
@Data
@TableName("omofun_poll_runs")
public class OmofunPollRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String startedAt;
    private String finishedAt;
    /** 0/1，与旧库一致；查询侧转 boolean */
    private Integer ok;
    private Integer pages;
    private Integer serverEntries;
    private Integer newEntries;
    private Integer updatedEntries;
    private Integer backfilledEntries;
    private Integer resetDetected;
    private String error;
}
