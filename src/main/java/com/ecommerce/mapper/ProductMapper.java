package com.ecommerce.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.entity.ProductDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper 接口
 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductDO> {

    /**
     * 扣减库存（乐观库存扣减：原子操作，避免超卖）
     *
     * @param productId 商品ID
     * @param quantity  扣减数量
     * @return 影响行数（=1 扣减成功；=0 库存不足）
     */
    @Update("UPDATE product SET stock = stock - #{quantity} WHERE id = #{productId} AND stock >= #{quantity} AND deleted = 0")
    int decreaseStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 增加库存（订单取消回滚时用）
     */
    @Update("UPDATE product SET stock = stock + #{quantity} WHERE id = #{productId} AND deleted = 0")
    int increaseStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
