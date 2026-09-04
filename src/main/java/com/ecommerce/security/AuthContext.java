package com.ecommerce.security;

/**
 * 当前登录用户上下文（ThreadLocal）
 *
 * <p>JwtAuthInterceptor 在请求进入时把解析出的 userId 放入本上下文；
 * 业务层可用 AuthContext.getUserId() 拿到"当前是谁"，从而不再信任请求参数里的 userId。</p>
 *
 * <p>注意：请求处理完必须调用 clear() 清理，否则线程池复用会导致"串号"（A 的请求读到 B 的身份）。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 设置当前用户（仅拦截器调用） */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    /** 获取当前用户ID；未登录/未设置时返回 null */
    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    /** 请求处理结束清理，防止线程复用串号 */
    public static void clear() {
        USER_ID_HOLDER.remove();
    }
}
