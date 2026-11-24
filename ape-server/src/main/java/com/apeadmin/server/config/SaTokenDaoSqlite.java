package com.apeadmin.server.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.auto.SaTokenDaoByObjectFollowString;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * sa-token SQLite 持久化 Dao：登录态（token→id 映射 / Account-Session / Token-Session /
 * refresh 令牌）落库 sa_token_store，服务重启后仍有效。
 *
 * <p>背景：此前用 SaTokenDaoDefaultImpl（纯内存），开发期每次重启服务，浏览器残留的合法
 * JWT 因 Dao 侧映射丢失被判定为「token 无效」，用户被迫重新登录。换成落库后重启不再掉线。
 *
 * <p>实现方式：继承 SaTokenDaoByObjectFollowString，只需实现 6 个 String 级方法 + searchData；
 * Object/Session 层由 sa-token 默认链路经 JSON 序列化自动转换（与官方 Redis Dao 同构）。
 * 过期行：懒删除（get 命中过期时顺手 DELETE）+ 周期性兜底清理（purgeExpired，
 * 覆盖「从不单独 get」的 key，避免 sa_token_store 只增不减）。
 * timeout 契约对齐官方 Redis Dao：timeout==0 或 <= NOT_VALUE_EXPIRE(-2) 时不存储。
 */
public class SaTokenDaoSqlite implements SaTokenDaoByObjectFollowString {

    private final DataSource dataSource;

    public SaTokenDaoSqlite(DataSource dataSource) {
        this.dataSource = dataSource;
        purgeExpired();  // 启动时先清一遍历史残留
    }

    /** 兜底清理：删除已过期行（NEVER_EXPIRE(-1) 永不过期行不受影响）；每 5 分钟一次。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public int purgeExpired() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM sa_token_store WHERE expire_at >= 0 AND expire_at < ?")) {
            ps.setLong(1, System.currentTimeMillis() / 1000);
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    // ==================== String 层（token 映射 / refresh 令牌核心读写） ====================

    @Override
    public String get(String key) {
        try (Connection conn = dataSource.getConnection()) {
            Row row = select(conn, key);
            if (row == null) {
                return null;
            }
            if (expired(row.expireAt)) {
                delete(conn, key);
                return null;
            }
            return row.value;
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 读取失败: " + key, e);
        }
    }

    @Override
    public void set(String key, String value, long timeout) {
        // 官方契约对齐：timeout==0 或 <= NOT_VALUE_EXPIRE(-2) 时不存储（此前 -2 会被误当「永不过期」落库）
        if (timeout == SaTokenDao.NOT_VALUE_EXPIRE || timeout == 0) {
            return;
        }
        long expireAt = toExpireAt(timeout);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO sa_token_store(key, value, expire_at) VALUES(?, ?, ?) "
                             + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, expire_at = excluded.expire_at")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setLong(3, expireAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 写入失败: " + key, e);
        }
    }

    @Override
    public void update(String key, String value) {
        // 契约：只更新 value 不动过期时间；key 不存在时无效（INSERT 不允许发生）
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE sa_token_store SET value = ? WHERE key = ?")) {
            ps.setString(1, value);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 更新失败: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try (Connection conn = dataSource.getConnection()) {
            delete(conn, key);
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 删除失败: " + key, e);
        }
    }

    @Override
    public long getTimeout(String key) {
        try (Connection conn = dataSource.getConnection()) {
            Row row = select(conn, key);
            if (row == null) {
                return SaTokenDao.NOT_VALUE_EXPIRE;
            }
            if (row.expireAt < 0) {
                return SaTokenDao.NEVER_EXPIRE;
            }
            long remain = row.expireAt - now();
            if (remain <= 0) {
                delete(conn, key);
                return SaTokenDao.NOT_VALUE_EXPIRE;
            }
            return remain;
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 读 TTL 失败: " + key, e);
        }
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        // 契约：改过期时间不动 value；key 不存在时无效；0 / <=NOT_VALUE_EXPIRE 时不动（官方语义）
        if (timeout == SaTokenDao.NOT_VALUE_EXPIRE || timeout == 0) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE sa_token_store SET expire_at = ? WHERE key = ?")) {
            ps.setLong(1, toExpireAt(timeout));
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 改 TTL 失败: " + key, e);
        }
    }

    // ==================== 检索（对齐 SaFoxUtil.searchList 语义） ====================

    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        // 默认实现语义：startsWith(prefix) && contains(keyword)；true=正序 / false=倒序；
        // size=-1 取全部；start<0 按 0 处理
        String p = (prefix == null ? "" : prefix);
        String kw = (keyword == null ? "" : keyword);
        int from = Math.max(start, 0);
        int limit = size;   // SQLite LIMIT 负数 = 不限制，与 size=-1 语义一致
        String sql = "SELECT key FROM sa_token_store "
                + "WHERE key LIKE ? ESCAPE '\\' AND key LIKE ? ESCAPE '\\' "
                + "ORDER BY key " + (sortType ? "ASC" : "DESC");
        if (limit >= 0) {
            sql += " LIMIT ? OFFSET ?";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, like(p) + "%");
            ps.setString(2, "%" + like(kw) + "%");
            if (limit >= 0) {
                ps.setInt(3, limit);
                ps.setInt(4, from);
            }
            List<String> keys = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
            return keys;
        } catch (SQLException e) {
            throw new IllegalStateException("sa-token Dao 检索失败: " + p + "|" + kw, e);
        }
    }

    // ==================== 内部工具 ====================

    private record Row(String value, long expireAt) {
    }

    private Row select(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value, expire_at FROM sa_token_store WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString(1), rs.getLong(2)) : null;
            }
        }
    }

    private void delete(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sa_token_store WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    /** timeout（秒）→ 到期 unix 秒；-1/负值 = 永不过期（NEVER_EXPIRE）。 */
    private long toExpireAt(long timeout) {
        return timeout < 0 ? SaTokenDao.NEVER_EXPIRE : now() + timeout;
    }

    private boolean expired(long expireAt) {
        return expireAt >= 0 && expireAt <= now();
    }

    private long now() {
        return System.currentTimeMillis() / 1000;
    }

    private String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
