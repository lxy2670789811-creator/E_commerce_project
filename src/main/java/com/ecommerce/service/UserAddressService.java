package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.entity.UserAddressDO;

import java.util.List;

/**
 * 用户地址 Service 接口
 */
public interface UserAddressService extends IService<UserAddressDO> {

    /**
     * 查询用户的地址列表
     */
    List<UserAddressDO> listByUserId(Long userId);

    /**
     * 新增地址
     */
    Long addAddress(UserAddressDO address);

    /**
     * 根据ID和用户ID查询地址（校验归属）
     */
    UserAddressDO getByIdAndUserId(Long addressId, Long userId);
}
