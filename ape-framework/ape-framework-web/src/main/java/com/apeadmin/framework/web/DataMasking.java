package com.apeadmin.framework.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 出参脱敏注解（SmartAdmin 模式）：标注在 DTO 字段上，Jackson 序列化期生效。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DataMasking {

    MaskType value();

    enum MaskType {
        /** 保留前4后4：sk-****abcd（API Key 等） */
        API_KEY,
        /** 手机号：138****5678 */
        PHONE,
        /** 邮箱：a***@domain.com */
        EMAIL,
        /** 全遮蔽 */
        ALL
    }
}
