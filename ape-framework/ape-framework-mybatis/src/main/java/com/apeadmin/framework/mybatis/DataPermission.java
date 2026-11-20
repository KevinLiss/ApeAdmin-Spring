package com.apeadmin.framework.mybatis;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限声明：标注在 Controller/Service 方法上，由切面捕获后交 MP 拦截器改写 SQL。
 * dept/user 为默认列名映射（JOIN 多表时按列存在性匹配生效）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DataPermission {

    boolean enable() default true;

    String deptColumn() default "dept_id";

    String userColumn() default "user_id";
}
