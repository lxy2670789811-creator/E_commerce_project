package com.ecommerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.entity.UserAddressDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户地址 Mapper 接口
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddressDO> {
}
