-- V1.6：sa-token 持久化存储表（登录态跨重启保留）
-- 背景：此前 Dao 为内存实现（SaTokenDaoDefaultImpl），服务重启即清空 token→loginId
-- 映射，浏览器残留的 JWT 被判定为「token 无效」，用户被迫重新登录。
-- key 形态（sa-token 拼接）：satoken:login:token:{token} / satoken:login:session:{id} 等。
-- expire_at：unix 秒时间戳；-1 表示永不过期（与 sa-token NEVER_EXPIRE 对齐）。
CREATE TABLE IF NOT EXISTS sa_token_store (
    key       TEXT PRIMARY KEY,
    value     TEXT NOT NULL,
    expire_at INTEGER NOT NULL DEFAULT -1
);
CREATE INDEX IF NOT EXISTS idx_sa_token_store_expire ON sa_token_store (expire_at);
