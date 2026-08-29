package com.ecommerce.convert;

import com.ecommerce.entity.OrderDO;
import com.ecommerce.enums.OrderStatusEnum;
import com.ecommerce.vo.order.OrderDetailVO;
import com.ecommerce.vo.order.OrderVO;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 订单对象转换器（MapStruct）
 */
@Mapper
public interface OrderConvert {

    OrderConvert INSTANCE = Mappers.getMapper(OrderConvert.class);

    /**
     * DO -> OrderVO（列表）
     */
    @Mappings({
            @Mapping(target = "statusText", expression = "java(statusToText(doEntity.getStatus()))")
    })
    OrderVO doToVO(OrderDO doEntity);

    /**
     * DO 列表 -> OrderVO 列表
     */
    @IterableMapping(elementTargetType = OrderVO.class)
    List<OrderVO> doListToVOList(List<OrderDO> doList);

    /**
     * DO -> OrderDetailVO（详情）
     */
    @Mappings({
            @Mapping(target = "statusText", expression = "java(statusToText(doEntity.getStatus()))")
    })
    OrderDetailVO doToDetailVO(OrderDO doEntity);

    /**
     * 状态码 -> 文本
     */
    default String statusToText(Integer status) {
        return OrderStatusEnum.getTextByCode(status);
    }
}
