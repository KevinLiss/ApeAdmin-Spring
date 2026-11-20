package com.apeadmin.chat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** sys_ai_provider：AI 模型供应商（OpenAI 兼容协议）。 */
@Data
@TableName("sys_ai_provider")
public class AiProviderDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String providerType;   // openai_compatible
    private String baseUrl;
    private String apiKeyEnc;      // AES-256-GCM 密文
    private String headers;        // JSON 额外请求头
    private String models;         // JSON 数组
    private Integer enabled;
    private Integer sort;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
