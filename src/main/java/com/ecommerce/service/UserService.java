package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.entity.UserDO;

/**
 * 用户 Service 接口（极简实现）
 */
public interface UserService extends IService<UserDO> {

    /**
     * 登录（BCrypt 校验密码）
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 签发的 JWT token
     */
    String login(String username, String password);

    /**
     * 按用户名查询用户
     */
    UserDO getUserByUsername(String username);

    /**
     * 根据ID获取用户
     */
    UserDO getUserById(Long id);
}
