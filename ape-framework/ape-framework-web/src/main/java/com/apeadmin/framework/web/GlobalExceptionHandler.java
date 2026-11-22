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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    /**
     * 鉴权失败（未登录/token 无效/过期/被顶/被踢）→ 真实 HTTP 401 + 友好文案。
     *
     * <p>对齐 Python 版契约：前端拦截器按 HTTP 状态 401 走优雅路径（清 token + 非登录页
     * 提示「登录已过期」并跳登录，登录页静默清理）；若包成 HTTP 200 + code=401，会落入
     * 通用错误分支，把消息原文（含整段 token）弹到页面上（实证：2026-11-21 登录页顶部
     * 出现大段「token 无效：eyJ…」红条）。
     *
     * <p>文案按 sa-token 异常类型映射，不回显 token 原文（避免泄露令牌、也避免吓到用户）。
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<Void>> handleNotLogin(NotLoginException e) {
        String msg = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN -> "未登录，请先登录";
            case NotLoginException.INVALID_TOKEN -> "登录状态无效，请重新登录";
            case NotLoginException.TOKEN_TIMEOUT -> "登录已过期，请重新登录";
            case NotLoginException.BE_REPLACED -> "账号已在其他设备登录，当前已下线";
            case NotLoginException.KICK_OUT -> "您已被强制下线";
            case NotLoginException.TOKEN_FREEZE -> "账号已被冻结，请联系管理员";
            case NotLoginException.NO_PREFIX -> "请求缺少认证信息";
            default -> "登录状态已失效，请重新登录";
        };
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ErrorCode.UNAUTHORIZED, msg));
    }

    /** 无权限/无角色 → 403。 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<R<Void>> handleNotPermission(Exception e) {
        return ResponseEntity.status(HttpStatus.OK).body(R.fail(ErrorCode.FORBIDDEN,
                e instanceof NotPermissionException npe
                        ? "无权限：" + npe.getPermission()
                        : "无权限：缺少角色 " + ((NotRoleException) e).getRole()));
    }

    /** 路径/查询参数类型不匹配（如前端把 undefined 当 id 传入）→ 400，而非兜底 500。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getParameter().getParameterName();
        log.warn("参数类型不匹配: {}={}", name, e.getValue());
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.BAD_REQUEST, "参数 " + name + " 格式不正确: " + e.getValue()));
    }

    /** 请求体 JSON 非法/缺失 → 400，而非兜底 500。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.BAD_REQUEST, "请求体格式不正确"));
    }

    /** 必填参数缺失（query/form）→ 400，而非兜底 500。 */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.BAD_REQUEST, "缺少必填参数: " + e.getParameterName()));
    }

    /** multipart 请求非法/缺失（未带 multipart Content-Type 等）→ 400。 */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<R<Void>> handleMultipart(org.springframework.web.multipart.MultipartException e) {
        log.warn("multipart 请求非法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.BAD_REQUEST, "请使用 multipart/form-data 上传文件"));
    }

    /** multipart 缺文件 → 400。 */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<R<Void>> handleMissingPart(org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.BAD_REQUEST, "缺少必填的文件字段: " + e.getRequestPartName()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.OK)
                .body(R.fail(ErrorCode.INTERNAL_ERROR, "服务器开小差了，请稍后再试"));
    }
}
