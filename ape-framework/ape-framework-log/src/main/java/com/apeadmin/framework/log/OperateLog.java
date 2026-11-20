package com.apeadmin.framework.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标注类（模块名）或方法（动作名），切面捕获后异步入库。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface OperateLog {

    /** 动作名，如「新增用户」；为空时取方法名。 */
    String action() default "";

    /** 模块名，为空时取类名。 */
    String module() default "";
}
