-- V1.4：dev_example 插件示例表（L1 插件 install 时 seed 数据；表名以插件名前缀）
CREATE TABLE IF NOT EXISTS dev_example_note (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    title      TEXT NOT NULL,
    content    TEXT DEFAULT '',
    created_at TEXT,
    updated_at TEXT
);
