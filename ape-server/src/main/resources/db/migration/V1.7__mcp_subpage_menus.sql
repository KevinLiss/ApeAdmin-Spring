-- V1.7：补齐 MCP 子页面菜单节点（对齐 Python 版独立入口）
-- 背景：Python 版「提示词列表 /mcp/prompts」「调用日志 /mcp/audit-logs」是独立菜单页
-- （前端视图 mcp/prompts.vue、mcp/audit-logs.vue 已在前端产物中），
-- Java 版此前仅在工具列表页内以按钮权限（53/54）体现，菜单树缺两个 C 节点。
-- 新增 id 75/76，并把 developer（2）/viewer（3）的可见性对齐 Python 语义。

INSERT INTO sys_menu (id, parent_id, type, name, path, component, permission, icon, sort, visible, status, i18n_key, created_at, updated_at)
VALUES
 (75, 50, 'C', '提示词列表', 'prompts', 'mcp/prompts', 'mcp:prompts:list', 'Tickets', 3, 1, 1, NULL, datetime('now'), datetime('now')),
 (76, 50, 'C', '调用日志', 'audit-logs', 'mcp/audit-logs', 'mcp:audit:list', 'Document', 4, 1, 1, NULL, datetime('now'), datetime('now'));

-- admin 已按 V1.1 的「全部菜单」策略补授（V1.1 用 SELECT 1,id FROM sys_menu，新节点需补）
INSERT INTO sys_role_menu (role_id, menu_id) SELECT 1, id FROM sys_menu WHERE id IN (75, 76);
-- developer：MCP 全部可见
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (2, 75), (2, 76);
-- viewer：仅提示词列表（调用日志含审计明细，对齐只读语义也开放——与 Python 一致开放列表）
INSERT INTO sys_role_menu (role_id, menu_id) VALUES (3, 75), (3, 76);
