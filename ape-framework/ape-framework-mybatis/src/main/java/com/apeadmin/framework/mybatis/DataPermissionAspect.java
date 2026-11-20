package com.apeadmin.framework.mybatis;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

/**
 * 数据权限切面：方法/类上的 @DataPermission 注解写入 ThreadLocal，SQL 执行后清理。
 */
@Aspect
public class DataPermissionAspect {

    @Around("@annotation(com.apeadmin.framework.mybatis.DataPermission)"
            + " || @within(com.apeadmin.framework.mybatis.DataPermission)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Method method = ((org.aspectj.lang.reflect.MethodSignature) pjp.getSignature()).getMethod();
            DataPermission anno = AnnotatedElementUtils.findMergedAnnotation(method, DataPermission.class);
            if (anno == null) {
                anno = AnnotatedElementUtils.findMergedAnnotation(pjp.getTarget().getClass(), DataPermission.class);
            }
            if (anno != null) {
                DataPermissionContextHolder.set(anno);
            }
            return pjp.proceed();
        } finally {
            DataPermissionContextHolder.clear();
        }
    }
}
