package com.dailysync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dailysync.entity.Attachment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** attachments 表读写。 */
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /**
     * 仓库附件已占用字节数（墓碑行的 size 已清 0，直接求和即有效占用）。
     *
     * <p>用一条聚合 SQL 而不是把行捞回来在内存里求和：配额检查在每次上传前都要跑，
     * 没必要为它把几百行元数据搬进 JVM。
     */
    @Select("SELECT COALESCE(SUM(size), 0) FROM attachments WHERE vault_id = #{vaultId}")
    long sumSizeByVault(@Param("vaultId") Long vaultId);
}
