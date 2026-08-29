package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.common.PageResult;
import com.ecommerce.dto.product.ProductAddDTO;
import com.ecommerce.dto.product.ProductStatusDTO;
import com.ecommerce.dto.product.ProductUpdateDTO;
import com.ecommerce.entity.ProductDO;
import com.ecommerce.vo.product.ProductStockVO;
import com.ecommerce.vo.product.ProductVO;

import java.util.List;

/**
 * 商品 Service 接口
 */
public interface ProductService extends IService<ProductDO> {

    /**
     * 新增商品
     */
    Long addProduct(ProductAddDTO dto);

    /**
     * 修改商品
     */
    void updateProduct(ProductUpdateDTO dto);

    /**
     * 删除商品（逻辑删除）
     */
    void deleteProduct(Long id);

    /**
     * 商品上下架
     */
    void updateStatus(ProductStatusDTO dto);

    /**
     * 根据ID查询商品详情（含Redis缓存）
     */
    ProductVO getProductDetail(Long id);

    /**
     * 分页查询商品列表
     */
    PageResult<ProductVO> listProducts(String keyword, String category, Integer status, long page, long pageSize);

    /**
     * 查询商品库存
     */
    ProductStockVO getProductStock(Long id);

    /**
     * 扣减库存（原子操作，供订单服务调用）
     *
     * @return true 扣减成功；false 库存不足
     */
    boolean decreaseStock(Long productId, Integer quantity);

    /**
     * 回滚库存（订单取消时调用）
     */
    boolean increaseStock(Long productId, Integer quantity);
}
