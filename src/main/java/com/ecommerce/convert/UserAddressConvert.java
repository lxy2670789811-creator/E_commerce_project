package com.ecommerce.convert;

import com.ecommerce.dto.user.UserAddressAddDTO;
import com.ecommerce.entity.UserAddressDO;
import com.ecommerce.vo.user.UserAddressVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 用户地址对象转换器
 */
@Mapper
public interface UserAddressConvert {

    UserAddressConvert INSTANCE = Mappers.getMapper(UserAddressConvert.class);

    UserAddressDO addDTOToDO(UserAddressAddDTO dto);

    @Mappings({
            @Mapping(target = "fullAddress", expression = "java(buildFullAddress(doEntity))")
    })
    UserAddressVO doToVO(UserAddressDO doEntity);

    List<UserAddressVO> doListToVOList(List<UserAddressDO> doList);

    default String buildFullAddress(UserAddressDO doEntity) {
        return doEntity.getProvince() + doEntity.getCity() + doEntity.getDistrict() + doEntity.getDetail();
    }
}
