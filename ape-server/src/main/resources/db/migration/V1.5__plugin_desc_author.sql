-- V1.5：sys_plugin 补 description/author 列（对齐插件管理页展示字段）
ALTER TABLE sys_plugin ADD COLUMN description TEXT;
ALTER TABLE sys_plugin ADD COLUMN author TEXT;
