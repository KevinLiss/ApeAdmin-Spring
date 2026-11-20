-- V1.3：sys_chat_message 补 tool_events 列（消息级工具事件回放，对齐 Python 版 ChatMessageModel）
ALTER TABLE sys_chat_message ADD COLUMN tool_events TEXT;
