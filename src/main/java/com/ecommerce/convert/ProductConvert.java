package com.ecommerce.convert;

import com.ecommerce.dto.product.ProductAddDTO;
import com.ecommerce.dto.product.ProductUpdateDTO;
import com.ecommerce.entity.ProductDO;
import com.ecommerce.vo.product.ProductStockVO;
import com.ecommerce.vo.product.ProductVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * 商品对象转换器（MapStruct 编译期转换）
 */
@Mapper
public interface ProductConvert {

    ProductConvert INSTANCE = Mappers.getMapper(ProductConvert.class);

    /**
     * 新增 DTO -> DO
     */
    ProductDO addDTOToDO(ProductAddDTO dto);

    /**
     * 修改 DTO -> DO
     */
    ProductDO updateDTOToDO(ProductUpdateDTO dto);

    /**
     * DO -> VO（含状态文本转换）
     */
    @Mappings({
            @Mapping(target = "statusText", expression = "java(statusToText(doEntity.getStatus()))")
    })
    ProductVO doToVO(ProductDO doEntity);

    /**
     * DO 列表 -> VO 列表
     */
    List<ProductVO> doListToVOList(List<ProductDO> doList);

    /**
     * DO -> 库存查询 VO
     */
    @Mappings({
            @Mapping(target = "productId", source = "id"),
            @Mapping(target = "productName", source = "name"),
            @Mapping(target = "hasStock", expression = "java(doEntity.getStock() != null && doEntity.getStock() > 0)"),
            @Mapping(target = "statusText", expression = "java(statusToText(doEntity.getStatus()))")
    })
    ProductStockVO doToStockVO(ProductDO doEntity);

    /**
     * 状态码 -> 文本
     */
    default String statusToText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "已上架";
            case 0 -> "已下架";
            default -> "未知";
        };
    }
}
