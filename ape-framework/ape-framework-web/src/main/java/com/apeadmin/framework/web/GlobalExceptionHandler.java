package com.apeadmin.framework.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.apeadmin.common.api.BizException;
import com.apeadmin.common.api.ErrorCode;
import com.apeadmin.common.api.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：BizException → R 包封；校验异常 → 400；兜底 → 500（日志含堆栈，响应不含）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        if (e.error() == ErrorCode.INTERNAL_ERROR) {
            log.error("业务异常(500)", e);
        }
        return ResponseEntity.status(HttpStatus.OK).body(R.fail(e.error(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? ErrorCode.BAD_REQUEST.msg() : fe.getField() + " " + fe.getDefaultMessage();
        return ResponseEntity.status(HttpStatus.OK).body(R.fail(ErrorCode.BAD_REQUEST, msg));
    }

    /** 鉴权失败（未登录/token 无效/过期/被顶/被踢）→ 401，消息用 sa-token 原文（前端按 code=401 跳登录）。 */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.OK).body(R.fail(ErrorCode.UNAUTHORIZED, e.getMessage()));
    }

    /** 无权限/无角色 → 403。 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<Void>> handleNotPermission(Exception e) {
        return ResponseEntity.status(HttpStatus.OK).body(R.fail(ErrorCode.FORBIDDEN,
                e instanceof NotPermissionException npe
                        ? "无权限：" + npe.getPermission()
                        : "无权限：缺少角色 " + ((NotRoleException) e).getRole()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.INTERNAL_ERROR, "服务器开小差了，请稍后再试"));
    }
}
