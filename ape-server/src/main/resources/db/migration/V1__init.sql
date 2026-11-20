-- ============================================================
-- ApeAdmin-Spring V1：全量建表（SQLite 方言；MySQL 切换注意点见注释）
-- 21 表 = RBAC 6 + AI 5 + MCP 2 + 插件 1 + 日志 2 + 设置 1 + 文件 2 + i18n 2
-- ============================================================

-- ---------- RBAC ----------
CREATE TABLE sys_user (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,   -- MySQL: BIGINT AUTO_INCREMENT PRIMARY KEY
    username       TEXT NOT NULL UNIQUE,
    password       TEXT NOT NULL,                       -- BCrypt
    nickname       TEXT,
    email          TEXT,
    phone          TEXT,
    avatar         TEXT,
    dept_id        INTEGER,
    is_super_admin INTEGER NOT NULL DEFAULT 0,
    status         INTEGER NOT NULL DEFAULT 1,
    last_login_at  TEXT,                                -- MySQL: DATETIME
    last_login_ip  TEXT,
    created_at     TEXT,
    updated_at     TEXT,
    deleted        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_user_dept ON sys_user(dept_id);

CREATE TABLE sys_role (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    code       TEXT NOT NULL UNIQUE,
    data_scope INTEGER NOT NULL DEFAULT 1,              -- 1本人/2本部门及以下/3本部门/4全部
    remark     TEXT,
    status     INTEGER NOT NULL DEFAULT 1,
    sort       INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    updated_at TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sys_menu (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id  INTEGER NOT NULL DEFAULT 0,
    type       TEXT NOT NULL,                           -- M目录/C菜单/F按钮
    name       TEXT NOT NULL,
    path       TEXT,
    component  TEXT,
    permission TEXT,
    icon       TEXT,
    sort       INTEGER NOT NULL DEFAULT 0,
    visible    INTEGER NOT NULL DEFAULT 1,
    status     INTEGER NOT NULL DEFAULT 1,
    i18n_key   TEXT,
    created_at TEXT,
    updated_at TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_menu_parent ON sys_menu(parent_id);

CREATE TABLE sys_dept (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id  INTEGER NOT NULL DEFAULT 0,
    name       TEXT NOT NULL,
    leader     TEXT,
    phone      TEXT,
    sort       INTEGER NOT NULL DEFAULT 0,
    status     INTEGER NOT NULL DEFAULT 1,
    created_at TEXT,
    updated_at TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sys_user_role (
    user_id INTEGER NOT NULL,
    role_id INTEGER NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_menu (
    role_id INTEGER NOT NULL,
    menu_id INTEGER NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);

-- ---------- AI ----------
CREATE TABLE sys_ai_provider (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    provider_type TEXT NOT NULL DEFAULT 'openai_compatible',
    base_url      TEXT NOT NULL,
    api_key_enc   TEXT,                                 -- AES-256-GCM 密文
    headers       TEXT DEFAULT '{}',                    -- JSON
    models        TEXT DEFAULT '[]',                    -- JSON 数组
    enabled       INTEGER NOT NULL DEFAULT 1,
    sort          INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT,
    updated_at    TEXT,
    deleted       INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sys_ai_model (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id      INTEGER NOT NULL,
    model            TEXT NOT NULL,
    support_tools    INTEGER NOT NULL DEFAULT 0,        -- 能力位：是否支持工具调用
    support_thinking INTEGER NOT NULL DEFAULT 0,        -- 能力位：是否支持思考过程
    temperature      REAL,
    max_tokens       INTEGER,
    created_at       TEXT,
    updated_at       TEXT,
    deleted          INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_ai_model_provider ON sys_ai_model(provider_id);

CREATE TABLE sys_chat_session (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id       INTEGER NOT NULL,
    title         TEXT DEFAULT '新对话',
    provider_id   INTEGER,
    model         TEXT,
    system_prompt TEXT,                                 -- 会话级快照（AIFlowy 模式）
    message_count INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT,
    updated_at    TEXT,
    deleted       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_chat_session_user ON sys_chat_session(user_id);

CREATE TABLE sys_chat_message (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        INTEGER NOT NULL,
    run_id            INTEGER,                          -- 消息↔运行关联（修 Go 版孤儿缺口）
    role              TEXT NOT NULL,                    -- user/assistant/system/tool
    content           TEXT NOT NULL DEFAULT '',         -- MySQL: LONGTEXT（含 tool_calls/reasoning JSON，可回放）
    round_number      INTEGER DEFAULT 0,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    status            TEXT DEFAULT 'done',              -- generating/done/failed/stopped
    version           INTEGER NOT NULL DEFAULT 0,       -- 乐观锁（整轮幂等落库）
    created_at        TEXT,
    updated_at        TEXT,
    deleted           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_chat_message_session ON sys_chat_message(session_id);

CREATE TABLE sys_chat_run (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        INTEGER NOT NULL,
    user_id           INTEGER NOT NULL,
    status            TEXT NOT NULL DEFAULT 'pending',  -- pending/running/done/failed/stopped
    provider_id       INTEGER,
    model             TEXT,
    enable_tools      INTEGER NOT NULL DEFAULT 1,
    question          TEXT,
    answer            TEXT DEFAULT '',                  -- 增量节流落库（500ms）
    tool_events       TEXT DEFAULT '[]',                -- MySQL: LONGTEXT
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    duration_ms       INTEGER,
    error             TEXT,
    started_at        TEXT,
    finished_at       TEXT,
    created_at        TEXT,
    updated_at        TEXT,
    deleted           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_chat_run_session ON sys_chat_run(session_id);
CREATE INDEX idx_sys_chat_run_status  ON sys_chat_run(status);

-- ---------- MCP ----------
CREATE TABLE sys_mcp_tool (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    name                 TEXT NOT NULL UNIQUE,
    title                TEXT,
    description          TEXT,
    input_schema         TEXT,                          -- JSON Schema
    category             TEXT,
    plugin_name          TEXT,
    required_permissions TEXT DEFAULT '[]',             -- JSON 数组
    handler_ref          TEXT,                          -- 重启恢复引用
    enabled              INTEGER NOT NULL DEFAULT 1,
    created_at           TEXT,
    updated_at           TEXT,
    deleted              INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sys_mcp_audit_log (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id        INTEGER,
    username       TEXT,
    action_type    TEXT NOT NULL,                       -- tool/resource/prompt
    target_name    TEXT,
    arguments      TEXT,
    result_preview TEXT,
    status         TEXT DEFAULT 'ok',
    duration_ms    INTEGER,
    created_at     TEXT,
    updated_at     TEXT,
    deleted        INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_mcp_audit_user ON sys_mcp_audit_log(user_id);

-- ---------- 插件 ----------
CREATE TABLE sys_plugin (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    title       TEXT,
    version     TEXT,
    level       TEXT DEFAULT 'L1',                      -- L1/L2/L3
    module_path TEXT,
    enabled     INTEGER NOT NULL DEFAULT 0,
    config      TEXT DEFAULT '{}',                      -- JSON
    created_at  TEXT,
    updated_at  TEXT,
    deleted     INTEGER NOT NULL DEFAULT 0
);

-- ---------- 日志 ----------
CREATE TABLE sys_operate_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER,
    username    TEXT,
    module      TEXT,
    action      TEXT,
    method      TEXT,
    path        TEXT,
    params      TEXT,                                   -- MySQL: TEXT（脱敏截断 3800 字）
    status_code INTEGER,
    duration_ms INTEGER,
    ip          TEXT,
    ip_region   TEXT,
    user_agent  TEXT,
    success     INTEGER NOT NULL DEFAULT 1,
    fail_reason TEXT,
    created_at  TEXT,
    updated_at  TEXT,
    deleted     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_operate_log_user ON sys_operate_log(user_id);
CREATE INDEX idx_sys_operate_log_time ON sys_operate_log(created_at);

CREATE TABLE sys_login_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER,
    username    TEXT,
    ip          TEXT,
    user_agent  TEXT,
    result      TEXT NOT NULL DEFAULT 'success',        -- success/failed/locked
    fail_reason TEXT,
    created_at  TEXT,
    updated_at  TEXT,
    deleted     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_sys_login_log_time ON sys_login_log(created_at);

-- ---------- 设置 ----------
CREATE TABLE sys_setting (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    key        TEXT NOT NULL UNIQUE,
    value      TEXT,
    is_public  INTEGER NOT NULL DEFAULT 0,              -- 免登录可读（登录页需要）
    remark     TEXT,
    created_at TEXT,
    updated_at TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);

-- ---------- 文件 ----------
CREATE TABLE sys_file_folder (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_id  INTEGER NOT NULL DEFAULT 0,
    name       TEXT NOT NULL,
    created_at TEXT,
    updated_at TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sys_file (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    folder_id   INTEGER NOT NULL DEFAULT 0,
    origin_name TEXT NOT NULL,
    stored_name TEXT NOT NULL,
    mime        TEXT,
    size        INTEGER NOT NULL DEFAULT 0,
    md5         TEXT,
    uploader_id INTEGER,
    created_at  TEXT,
    updated_at  TEXT,
    deleted     INTEGER NOT NULL DEFAULT 0
);

-- ---------- i18n ----------
CREATE TABLE sys_locale (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    code       TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    is_enabled INTEGER NOT NULL DEFAULT 1,
    is_default INTEGER NOT NULL DEFAULT 0,
    created_at TEXT,
    updated_at TEXT,
    deleted    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE sys_translation (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    locale_code TEXT NOT NULL,
    namespace   TEXT NOT NULL DEFAULT 'common',
    key         TEXT NOT NULL,
    value       TEXT,
    created_at  TEXT,
    updated_at  TEXT,
    deleted     INTEGER NOT NULL DEFAULT 0,
    UNIQUE (locale_code, namespace, key)
);
