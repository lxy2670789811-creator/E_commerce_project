package com.ecommerce.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler} 的 HTTP 状态码语义测试
 *
 * <p>锁定一条约定：<b>HTTP 层的失败（路径不存在 / 方法不支持 / 未预期异常）必须返回真实的 4xx、5xx，
 * 不能被兜底处理器包成 HTTP 200</b>。
 *
 * <p>⚠️ 断言必须<b>同时</b>看 HTTP 状态码和 body.code。旧实现下 body.code 是 5000、
 * 新实现下是 404/405 —— 只断言其中一个都可能让"空转"的实现蒙混过关。
 * 第 6 个用例是对照组：业务异常仍然必须是 HTTP 200，本次修复不得改变这条既有约定。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("未映射路径：返回 HTTP 404，而不是被兜底包成 200 + code 5000")
    void unmappedPathReturns404() {
        // Spring Framework 6.1 起，DispatcherServlet 找不到资源时抛的就是这个异常。
        // /actuator/metrics 正是本次修复的触发场景：端点没暴露 ⇒ 等价于路径不存在。
        NoResourceFoundException e = new NoResourceFoundException(HttpMethod.GET, "actuator/metrics");

        ResponseEntity<Result<Void>> resp = handler.handleNotFoundException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode())
                .isNotEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    @DisplayName("NoHandlerFoundException 走同一条 404 分支")
    void noHandlerFoundReturns404() {
        NoHandlerFoundException e = new NoHandlerFoundException("GET", "/api/no-such-endpoint", new HttpHeaders());

        ResponseEntity<Result<Void>> resp = handler.handleNotFoundException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("请求方法不支持：返回 HTTP 405，且按 RFC 9110 带上 Allow 头")
    void methodNotSupportedReturns405WithAllowHeader() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        ResponseEntity<Result<Void>> resp = handler.handleMethodNotSupportedException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED.getCode());
        // 405 不带 Allow 头是 HTTP 规范不符：调用方无法知道该用哪个方法
        assertThat(resp.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
    }

    @Test
    @DisplayName("未预期异常：返回 HTTP 500（原实现伪装成 200，监控错误率恒为 0）")
    void unexpectedExceptionReturns500() {
        ResponseEntity<Result<Void>> resp = handler.handleException(new IllegalStateException("boom"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    @DisplayName("兜底遇框架级 ErrorResponse：沿用其自带状态码，不再改写成 200")
    void errorResponseKeepsItsOwnStatus() {
        // 415 在 MVC 里真实会发生（前端 Content-Type 写错），它自带 415 语义
        HttpMediaTypeNotSupportedException e = new HttpMediaTypeNotSupportedException("application/xml");

        ResponseEntity<Result<Void>> resp = handler.handleException(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    }

    @Test
    @DisplayName("对照组：业务异常仍是 HTTP 200 + body.code，本次修复不得改变这一约定")
    void businessExceptionStillReturnsHttp200() {
        BusinessException e = new BusinessException(ErrorCode.PRODUCT_STOCK_INSUFFICIENT);

        Result<Void> result = handler.handleBusinessException(e);

        // 返回裸 Result ⇒ Spring 默认渲染为 HTTP 200。
        // "库存不足"是正常的业务结果、不是传输层故障，前端拦截器靠 code 分支处理；
        // 若有人把它改成 ResponseEntity 并塞 4xx，前端会把正常业务提示当成网络错误 —— 这里就是防线。
        assertThat(result.getCode()).isEqualTo(ErrorCode.PRODUCT_STOCK_INSUFFICIENT.getCode());
        assertThat(result.getMessage()).isEqualTo(ErrorCode.PRODUCT_STOCK_INSUFFICIENT.getMessage());
    }
}
