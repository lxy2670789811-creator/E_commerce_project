package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.entity.UserDO;

/**
 * 用户 Service 接口（极简实现）
 */
public interface UserService extends IService<UserDO> {

    /**
     * 模拟登录
     *
     * @param username 用户名
     * @param password 密码
     * @return 用户ID
     */
    Long login(String username, String password);

    /**
     * 根据ID获取用户
     */
    UserDO getUserById(Long id);
}
