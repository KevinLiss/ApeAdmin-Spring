package com.apeadmin.framework.log;

import com.apeadmin.common.util.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 操作日志切面（RVP 事件化模式）：计时 + 参数脱敏截断 + publishEvent 异步解耦。
 */
@Slf4j
@Aspect
public class OperateLogAspect {

    private static final int PARAMS_MAX = 3800;

    private final ApplicationEventPublisher publisher;

    public OperateLogAspect(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Around("@annotation(com.apeadmin.framework.log.OperateLog)"
            + " || @within(com.apeadmin.framework.log.OperateLog)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        Throwable error = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            publish(pjp, start, error);
        }
    }

    private void publish(ProceedingJoinPoint pjp, long start, Throwable error) {
        try {
            Method method = ((org.aspectj.lang.reflect.MethodSignature) pjp.getSignature()).getMethod();
            OperateLog anno = AnnotatedElementUtils.findMergedAnnotation(method, OperateLog.class);
            OperateLog classAnno = AnnotatedElementUtils.findMergedAnnotation(pjp.getTarget().getClass(), OperateLog.class);
            if (anno == null && classAnno == null) {
                return;
            }

            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest req = attrs == null ? null : attrs.getRequest();
            HttpServletResponse resp = attrs == null ? null : attrs.getResponse();

            String module = anno != null && !anno.module().isBlank() ? anno.module()
                    : (classAnno != null && !classAnno.module().isBlank() ? classAnno.module() : "");
            String action = anno != null && !anno.action().isBlank() ? anno.action() : method.getName();

            OperateLogEvent event = OperateLogEvent.builder()
                    .module(module)
                    .action(action)
                    .method(req == null ? "" : req.getMethod())
                    .path(req == null ? "" : req.getRequestURI())
                    .params(truncate(buildParams(pjp.getArgs()), PARAMS_MAX))
                    .statusCode(resp == null ? null : resp.getStatus())
                    .durationMs(System.currentTimeMillis() - start)
                    .ip(req == null ? "" : clientIp(req))
                    .userAgent(req == null ? "" : truncate(req.getHeader("User-Agent"), 200))
                    .success(error == null)
                    .failReason(error == null ? null : truncate(error.getMessage(), 500))
                    .build();
            publisher.publishEvent(event);
        } catch (Exception ex) {
            log.warn("操作日志发布失败（不影响业务）", ex);
        }
    }

    private String buildParams(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        try {
            List<Object> safe = Arrays.stream(args)
                    .filter(a -> !(a instanceof MultipartFile)
                            && !(a instanceof jakarta.servlet.ServletRequest)
                            && !(a instanceof jakarta.servlet.ServletResponse))
                    .toList();
            String json = JsonUtils.toJson(safe);
            return maskSensitive(json);
        } catch (Exception e) {
            return "(参数序列化失败)";
        }
    }

    /** 简单脱敏：密码字段值替换。 */
    static String maskSensitive(String json) {
        return json.replaceAll("(\"(?:password|oldPassword|newPassword|api_key|api_key_enc|apiKey)\"\s*:\s*\")([^\"]*)(\")",
                "$1****$3");
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
