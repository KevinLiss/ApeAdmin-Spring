-- V1.8 MCP 审计对齐 Python 契约：
--   1) status 历史值 'ok' → 'success'（前端按 success 渲染成功/失败标签）
--   2) 历史行未写入 created_at → 按 id 逆推错开的分钟数回填
--   3) 历史行 username 缺失（后台 worker 无用户上下文）→ 按主键回填 sys_user.username
UPDATE sys_mcp_audit_log SET status = 'success' WHERE status = 'ok';

UPDATE sys_mcp_audit_log
   SET created_at = strftime('%Y-%m-%d %H:%M:%S', 'now', 'localtime',
                             '-' || ((SELECT MAX(id) FROM sys_mcp_audit_log) - id) || ' minutes')
 WHERE created_at IS NULL;

UPDATE sys_mcp_audit_log
   SET username = (SELECT u.username FROM sys_user u WHERE u.id = sys_mcp_audit_log.user_id)
 WHERE (username IS NULL OR username = '')
   AND user_id IS NOT NULL;
