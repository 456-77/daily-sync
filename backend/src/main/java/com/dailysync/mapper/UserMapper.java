package com.dailysync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dailysync.entity.User;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** users 表读写。 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 用户列表（含仓库数与记录数），供管理员界面。
     *
     * <p>用相关子查询而不是 JOIN + GROUP BY：这个项目的用户量很小，
     * 可读性比那点性能差异重要。别名写成 camelCase，避免依赖
     * map-underscore-to-camel-case 对 Map 结果的行为差异。
     */
    @Select("""
            SELECT u.id AS id,
                   u.username AS username,
                   u.nick_name AS nickName,
                   u.email AS email,
                   u.role AS role,
                   u.status AS status,
                   u.created_at AS createdAt,
                   (SELECT COUNT(*) FROM vaults v WHERE v.user_id = u.id) AS vaultCount,
                   (SELECT COUNT(*) FROM daily_records r
                     WHERE r.vault_id IN (SELECT v2.id FROM vaults v2 WHERE v2.user_id = u.id)) AS recordCount
            FROM users u
            ORDER BY u.id
            """)
    List<Map<String, Object>> selectAllWithStats();
}
