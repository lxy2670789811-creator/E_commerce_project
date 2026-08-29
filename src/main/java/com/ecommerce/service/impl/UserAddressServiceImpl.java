package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.entity.UserAddressDO;
import com.ecommerce.mapper.UserAddressMapper;
import com.ecommerce.service.UserAddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户地址 Service 实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAddressServiceImpl extends ServiceImpl<UserAddressMapper, UserAddressDO> implements UserAddressService {

    @Override
    public List<UserAddressDO> listByUserId(Long userId) {
        LambdaQueryWrapper<UserAddressDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAddressDO::getUserId, userId)
                .orderByDesc(UserAddressDO::getIsDefault)
                .orderByDesc(UserAddressDO::getCreateTime);
        return this.list(wrapper);
    }

    @Override
    public Long addAddress(UserAddressDO address) {
        // 如果设为默认，先取消该用户其他默认地址
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            clearDefaultAddress(address.getUserId());
        }
        this.save(address);
        log.info("新增地址成功：addressId={}, userId={}", address.getId(), address.getUserId());
        return address.getId();
    }

    @Override
    public UserAddressDO getByIdAndUserId(Long addressId, Long userId) {
        LambdaQueryWrapper<UserAddressDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAddressDO::getId, addressId)
                .eq(UserAddressDO::getUserId, userId);
        UserAddressDO address = this.getOne(wrapper);
        if (address == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        return address;
    }

    /**
     * 取消该用户的默认地址
     */
    private void clearDefaultAddress(Long userId) {
        LambdaQueryWrapper<UserAddressDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAddressDO::getUserId, userId)
                .eq(UserAddressDO::getIsDefault, 1);
        List<UserAddressDO> defaults = this.list(wrapper);
        for (UserAddressDO addr : defaults) {
            addr.setIsDefault(0);
            this.updateById(addr);
        }
    }
}
