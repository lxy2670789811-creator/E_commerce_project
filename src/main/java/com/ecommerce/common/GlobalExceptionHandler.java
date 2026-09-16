package com.ecommerce.common;

import jakarta.servlet.ServletException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p><b>本类最重要的一条约定：两类失败必须分开，绝不能混为一谈。</b>
 * <ol>
 *   <li><b>业务失败</b>（库存不足、订单不存在、参数校验不通过…）→ HTTP <b>200</b> + {@code body.code} 非 0。
 *       语义是"请求被正常处理了，结果是失败"，前端拦截器统一读 {@code code} 分支处理。</li>
 *   <li><b>HTTP 层失败</b>（路径不存在、方法不支持、媒体类型不支持、未预期异常）→ <b>真实的 4xx / 5xx</b>。
 *       语义是"请求根本没被正确处理"，状态码本身必须如实反映 —— 否则网关重试策略、K8s 探针、
 *       APM 错误率、前端 axios 拦截器全都只能看到 200，<b>失败被伪装成成功</b>，故障静默。</li>
 * </ol>
 *
 * <p><b>历史缺陷与本次修复（2026-09-16）</b>：原实现的兜底
 * {@code @ExceptionHandler(Exception.class)} 把包括 Spring 框架异常在内的一切都包成
 * HTTP 200 + {@code code=5000}，第 2 类被整个抹掉。最典型的受害者是
 * <b>actuator 未暴露端点</b>：{@code GET /api/actuator/metrics} 本该是干净的 404，
 * 却表现为"后端返回了系统异常"，看起来像服务故障。修法见下面三个新增 handler。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理自定义业务异常
     *
     * <p>刻意返回裸 {@code Result}（HTTP 200）：业务失败不是传输层故障，见类注释第 1 条。
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage(), e);
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理 @RequestBody 参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMsg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("请求体参数校验失败: {}", errorMsg);
        return Result.fail(ErrorCode.PARAM_ERROR, errorMsg);
    }

    /**
     * 处理 @Validated + @RequestParam 参数校验异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String errorMsg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("请求参数校验失败: {}", errorMsg);
        return Result.fail(ErrorCode.PARAM_ERROR, errorMsg);
    }

    /**
     * 处理 form 表单参数绑定异常
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String errorMsg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("表单参数绑定失败: {}", errorMsg);
        return Result.fail(ErrorCode.PARAM_ERROR, errorMsg);
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        String errorMsg = "缺少必填参数: " + e.getParameterName();
        log.warn(errorMsg);
        return Result.fail(ErrorCode.PARAM_ERROR, errorMsg);
    }

    /**
     * 处理参数类型不匹配异常
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String errorMsg = String.format("参数类型不匹配: %s 需要类型 %s",
                e.getName(), e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "");
        log.warn(errorMsg);
        return Result.fail(ErrorCode.PARAM_ERROR, errorMsg);
    }

    /**
     * 处理请求体解析异常
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_ERROR, "请求体格式错误，请检查JSON格式");
    }

    /**
     * 处理"路径/资源不存在"异常 —— 返回真正的 <b>HTTP 404</b>。
     *
     * <p><b>为什么必须单独接住它</b>：Spring MVC 找不到映射时抛的是
     * {@link NoResourceFoundException}（Spring Framework 6.1 起由静态资源处理器抛出，
     * 与旧的 {@link NoHandlerFoundException} 并列），它本身就是 {@link ErrorResponse}，
     * 自带 404 语义。但在原实现里，它会被 {@link #handleException} 兜底捕获，
     * 于是 404 被强制改写成 HTTP 200 + {@code code=5000}。
     *
     * <p>后果有两个，都不轻：
     * <ol>
     *   <li><b>调用方无法用状态码判断"端点/路径不存在"。</b>最典型的就是 actuator 未暴露端点：
     *       {@code GET /api/actuator/metrics} 会被读成"后端返回了系统异常"，看着像服务故障，
     *       实际只是"这个端点没开"。监控、探针、接口调试工具全部误判。</li>
     *   <li><b>扫描器噪声污染日志与错误率。</b>公网扫描器会成片请求 {@code /admin.php}、{@code /.env}
     *       之类的路径，原实现下每一条都走 {@code log.error} 打完整堆栈，还被计进错误率指标 ——
     *       而这本该只是 404 级别的正常噪声。</li>
     * </ol>
     *
     * <p>日志刻意用 {@code debug}：4xx 是调用方的问题，不是服务端故障，不该进 error/warn。
     * 需要排查时把本类日志级别调到 DEBUG 即可（或直接看网关/Tomcat 的 access log）。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFoundException(ServletException e) {
        log.debug("请求路径不存在（未映射路径 / 端点未暴露）: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(ErrorCode.NOT_FOUND));
    }

    /**
     * 处理"请求方法不支持"异常 —— 返回真正的 <b>HTTP 405</b>。
     *
     * <p>与 404 同源：{@link HttpRequestMethodNotSupportedException} 也是 {@link ErrorResponse}，
     * 原先同样被兜底吞成 200。典型触发场景是前端把 POST 写成了 GET，或对只读接口发了 DELETE。
     * 这类"调用方写错了"的问题必须让状态码如实暴露，否则排查时只能靠猜。
     *
     * <p>按 RFC 9110，405 响应<b>必须</b>带 {@code Allow} 头告知支持哪些方法。
     * Spring 在构造该异常时已把支持的方法放进 {@code getHeaders()}，这里直接沿用，不重复拼装。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: method={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(e.getHeaders())
                .body(Result.fail(ErrorCode.METHOD_NOT_ALLOWED, "请求方法不支持: " + e.getMethod()));
    }

    /**
     * 兜底异常处理 —— 按语义分流，不再一律伪装成 200。
     *
     * <p><b>这里做过一次修正</b>：原实现是 {@code return Result.fail(ErrorCode.SYSTEM_ERROR);}，
     * 返回裸对象 ⇒ Spring 默认给 HTTP 200。等于把 HTTP 状态码这一层语义整个抹掉，
     * 网关、探针、APM、前端拦截器全都看不到失败。现在分两支：
     * <ul>
     *   <li>仍带 HTTP 语义的框架异常（{@link ErrorResponse}，如 415 媒体类型不支持、406 不可接受）：
     *       沿用其自带状态码，不再改写成 200；</li>
     *   <li>其余真正未预期的异常：HTTP <b>500</b> + {@code code=5000}。</li>
     * </ul>
     *
     * <p>注意 404 / 405 有更具体的 handler，Spring 会优先匹配它们，不会落到这里。
     * {@link #handleBusinessException}（200）及各类参数校验 handler 同样不受影响。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            log.warn("请求处理失败: status={}, message={}", status.value(), e.getMessage());
            String message = e.getMessage() != null ? e.getMessage() : "请求无法处理";
            return ResponseEntity.status(status).body(Result.fail(status.value(), message));
        }
        log.error("系统未捕获异常: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }
}
