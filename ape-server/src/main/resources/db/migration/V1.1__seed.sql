-- ============================================================
-- V1.1 种子数据：部门/超管/角色/全量权限菜单（含 Python 版首装漏掉的增量权限）/设置/i18n
-- 初始超管：admin / ApeAdmin@2026（BCrypt，首次登录后请修改）
-- ============================================================

-- ---------- 部门 ----------
INSERT INTO sys_dept (id, parent_id, name, leader, sort, status, created_at, updated_at) VALUES
 (1, 0, 'ApeAdmin', NULL, 1, 1, datetime('now'), datetime('now'));

-- ---------- 超管 ----------
INSERT INTO sys_user (id, username, password, nickname, dept_id, is_super_admin, status, created_at, updated_at) VALUES
 (1, 'admin', '$2a$10$eprQiLYLxvy1AR3wQIYlQuSc5/vB3vFVeJ/cJ3z4hnD8s5GMAWi3S', '超级管理员', 1, 1, 1, datetime('now'), datetime('now')),
 (2, 'dev',   '$2a$10$eprQiLYLxvy1AR3wQIYlQuSc5/vB3vFVeJ/cJ3z4hnD8s5GMAWi3S', '开发运维', 1, 0, 1, datetime('now'), datetime('now')),
 (3, 'viewer','$2a$10$eprQiLYLxvy1AR3wQIYlQuSc5/vB3vFVeJ/cJ3z4hnD8s5GMAWi3S', '访客', 1, 0, 1, datetime('now'), datetime('now'));

-- ---------- 角色 ----------
-- 对齐 Python 版：admin=全部数据(4)，developer/viewer=仅本人(1)；id 与下方 user_role/role_menu 引用一致
INSERT INTO sys_role (id, name, code, data_scope, remark, status, sort, created_at, updated_at) VALUES
 (1, '超级管理员', 'admin',     4, '系统内置超级管理员角色（全部数据）', 1, 1, datetime('now'), datetime('now')),
 (2, '开发者',     'developer', 1, '开发运维角色（仅本人数据）',         1, 2, datetime('now'), datetime('now')),
 (3, '访客',       'viewer',    1, '只读访客角色（仅本人数据）',         1, 3, datetime('now'), datetime('now'));

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1), (2, 2), (3, 3);

-- ---------- 菜单（底座首装种子；插件菜单由各插件自行种植） ----------
-- 说明：字段 (id, parent_id, type, name, path, component, permission, icon, sort)
INSERT INTO sys_menu (id, parent_id, type, name, path, component, permission, icon, sort, visible, status, created_at, updated_at) VALUES
 -- 仪表盘
 (1,  0, 'C', '系统仪表盘', '/dashboard-monitor', 'apeui/dashboard/Monitor', 'dashboard:view', 'Monitor', 1, 1, 1, datetime('now'), datetime('now')),
 -- 系统管理
 (2,  0, 'M', '系统管理', '/system', NULL, NULL, 'Setting', 10, 1, 1, datetime('now'), datetime('now')),
 (3,  2, 'C', '用户管理', 'user', 'system/user/index', 'system:user:list', 'User', 1, 1, 1, datetime('now'), datetime('now')),
 (4,  3, 'F', '新增用户', NULL, NULL, 'system:user:add', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (5,  3, 'F', '编辑用户', NULL, NULL, 'system:user:edit', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (6,  3, 'F', '删除用户', NULL, NULL, 'system:user:delete', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (7,  3, 'F', '重置密码', NULL, NULL, 'system:user:reset-password', NULL, 4, 1, 1, datetime('now'), datetime('now')),
 (8,  2, 'C', '角色管理', 'role', 'system/role/index', 'system:role:list', 'UserFilled', 2, 1, 1, datetime('now'), datetime('now')),
 (9,  8, 'F', '新增角色', NULL, NULL, 'system:role:add', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (10, 8, 'F', '编辑角色', NULL, NULL, 'system:role:edit', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (11, 8, 'F', '删除角色', NULL, NULL, 'system:role:delete', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (12, 2, 'C', '菜单管理', 'menu', 'system/menu/index', 'system:menu:list', 'Menu', 3, 1, 1, datetime('now'), datetime('now')),
 (13, 12, 'F', '新增菜单', NULL, NULL, 'system:menu:add', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (14, 12, 'F', '编辑菜单', NULL, NULL, 'system:menu:edit', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (15, 12, 'F', '删除菜单', NULL, NULL, 'system:menu:delete', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (16, 2, 'C', '部门管理', 'dept', 'system/dept/index', 'system:dept:list', 'OfficeBuilding', 4, 1, 1, datetime('now'), datetime('now')),
 (17, 16, 'F', '新增部门', NULL, NULL, 'system:dept:add', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (18, 16, 'F', '编辑部门', NULL, NULL, 'system:dept:edit', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (19, 16, 'F', '删除部门', NULL, NULL, 'system:dept:delete', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (20, 2, 'C', '系统设置', 'settings', 'system/settings/index', 'system:setting:list', 'Tools', 5, 1, 1, datetime('now'), datetime('now')),
 (21, 20, 'F', '编辑设置', NULL, NULL, 'system:setting:edit', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (22, 20, 'F', '版本信息', NULL, NULL, 'system:version:view', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (23, 20, 'F', '在线更新', NULL, NULL, 'system:version:update', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (24, 2, 'C', '文件管理', 'file', 'system/file/index', 'system:file:list', 'FolderOpened', 6, 1, 1, datetime('now'), datetime('now')),
 (25, 24, 'F', '上传文件', NULL, NULL, 'system:file:upload', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (26, 24, 'F', '新建文件夹', NULL, NULL, 'system:file:create-folder', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (27, 24, 'F', '重命名文件', NULL, NULL, 'system:file:rename', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (28, 24, 'F', '移动文件', NULL, NULL, 'system:file:move', NULL, 4, 1, 1, datetime('now'), datetime('now')),
 (29, 24, 'F', '删除文件', NULL, NULL, 'system:file:delete', NULL, 5, 1, 1, datetime('now'), datetime('now')),
 (30, 24, 'F', '下载文件', NULL, NULL, 'system:file:download', NULL, 6, 1, 1, datetime('now'), datetime('now')),
 (31, 2, 'C', '系统日志', 'log', 'system/log/index', 'system:log:list', 'Document', 7, 1, 1, datetime('now'), datetime('now')),
 (32, 31, 'F', '删除日志', NULL, NULL, 'system:log:delete', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 -- 插件管理
 (40, 0, 'C', '插件管理', '/plugin', 'system/plugin/index', 'system:plugin:list', 'Box', 20, 1, 1, datetime('now'), datetime('now')),
 (41, 40, 'F', '启用/禁用插件', NULL, NULL, 'system:plugin:toggle', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (42, 40, 'F', '插件配置', NULL, NULL, 'system:plugin:config', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (43, 40, 'F', '导入插件', NULL, NULL, 'system:plugin:upload', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (44, 40, 'F', '删除插件', NULL, NULL, 'system:plugin:delete', NULL, 4, 1, 1, datetime('now'), datetime('now')),
 (45, 40, 'F', '重启后端', NULL, NULL, 'system:plugin:restart', NULL, 5, 1, 1, datetime('now'), datetime('now')),
 -- MCP 管理
 (50, 0, 'M', 'MCP 管理', '/mcp', NULL, NULL, 'Connection', 30, 1, 1, datetime('now'), datetime('now')),
 (51, 50, 'C', '工具列表', 'tools', 'mcp/tools', 'mcp:tools:list', 'Tools', 1, 1, 1, datetime('now'), datetime('now')),
 (52, 51, 'F', '调用工具', NULL, NULL, 'mcp:tools:call', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (53, 51, 'F', '提示词列表', NULL, NULL, 'mcp:prompts:list', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (54, 51, 'F', '审计日志', NULL, NULL, 'mcp:audit:list', NULL, 3, 1, 1, datetime('now'), datetime('now')),
 (55, 50, 'C', '资源列表', 'resources', 'mcp/resources', 'mcp:resources:list', 'FolderOpened', 2, 1, 1, datetime('now'), datetime('now')),
 -- AI 助手
 (60, 0, 'M', 'AI 助手', '/ai', NULL, NULL, 'ChatDotRound', 40, 1, 1, datetime('now'), datetime('now')),
 (61, 60, 'C', 'AI 全能助手', 'chat', 'ai/chat/index', 'ai:chat', 'ChatLineRound', 1, 1, 1, datetime('now'), datetime('now')),
 (62, 61, 'F', '发起对话', NULL, NULL, 'ai:chat:call', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (63, 60, 'C', '模型密钥管理', 'providers', 'ai/providers/index', 'ai:provider:list', 'Key', 2, 1, 1, datetime('now'), datetime('now')),
 (64, 63, 'F', '新增模型密钥', NULL, NULL, 'ai:provider:add', NULL, 1, 1, 1, datetime('now'), datetime('now')),
 (65, 63, 'F', '编辑模型密钥', NULL, NULL, 'ai:provider:edit', NULL, 2, 1, 1, datetime('now'), datetime('now')),
 (66, 63, 'F', '删除模型密钥', NULL, NULL, 'ai:provider:delete', NULL, 3, 1, 1, datetime('now'), datetime('now'));

-- ---------- 角色菜单 ----------
-- admin 拥有全部
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;
-- developer：仪表盘 + 系统管理(只读 list) + MCP 全部 + AI 全部
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
 (2, 1), (2, 2), (2, 3), (2, 8), (2, 12), (2, 16), (2, 20), (2, 24), (2, 31),
 (2, 40), (2, 41), (2, 42), (2, 43), (2, 44), (2, 45),
 (2, 50), (2, 51), (2, 52), (2, 53), (2, 54), (2, 55),
 (2, 60), (2, 61), (2, 62), (2, 63), (2, 64), (2, 65), (2, 66);
-- viewer：仪表盘 + 各 list 只读
INSERT INTO sys_role_menu (role_id, menu_id) VALUES
 (3, 1), (3, 2), (3, 3), (3, 8), (3, 12), (3, 16), (3, 20), (3, 24), (3, 31),
 (3, 40), (3, 50), (3, 51), (3, 55), (3, 60), (3, 61), (3, 63);

-- ---------- 设置 ----------
INSERT INTO sys_setting (key, value, is_public, remark, created_at, updated_at) VALUES
 ('site_name',    'ApeAdmin',  1, '站点名称', datetime('now'), datetime('now')),
 ('site_logo',    NULL,        1, '站点 Logo', datetime('now'), datetime('now')),
 ('admin_path',   '/admin',    0, '管理后台路径（前端硬编码，固定 /admin）', datetime('now'), datetime('now')),
 ('default_locale','zh-CN',    1, '默认语言', datetime('now'), datetime('now'));

-- ---------- i18n ----------
INSERT INTO sys_locale (code, name, is_enabled, is_default, created_at, updated_at) VALUES
 ('zh-CN', '简体中文', 1, 1, datetime('now'), datetime('now')),
 ('en-US', 'English',  1, 0, datetime('now'), datetime('now'));
