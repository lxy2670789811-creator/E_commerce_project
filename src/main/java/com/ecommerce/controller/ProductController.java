package com.ecommerce.controller;

import com.ecommerce.common.PageResult;
import com.ecommerce.common.Result;
import com.ecommerce.dto.product.ProductAddDTO;
import com.ecommerce.dto.product.ProductStatusDTO;
import com.ecommerce.dto.product.ProductUpdateDTO;
import com.ecommerce.service.ProductService;
import com.ecommerce.vo.product.ProductStockVO;
import com.ecommerce.vo.product.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商品模块 Controller
 */
@Tag(name = "商品模块", description = "商品增删改查、上下架、库存查询")
@Validated
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "新增商品", description = "创建一个新的商品")
    @PostMapping("/add")
    public Result<Long> addProduct(@RequestBody @Valid ProductAddDTO dto) {
        Long productId = productService.addProduct(dto);
        return Result.success(productId);
    }

    @Operation(summary = "修改商品", description = "根据商品ID更新商品信息，更新后自动删除Redis缓存")
    @PutMapping("/update")
    public Result<Void> updateProduct(@RequestBody @Valid ProductUpdateDTO dto) {
        productService.updateProduct(dto);
        return Result.success();
    }

    @Operation(summary = "删除商品", description = "根据ID逻辑删除商品，同时清除缓存")
    @DeleteMapping("/delete")
    public Result<Void> deleteProduct(
            @Parameter(description = "商品ID", required = true, example = "1")
            @RequestParam @NotNull(message = "商品ID不能为空") Long id) {
        productService.deleteProduct(id);
        return Result.success();
    }

    @Operation(summary = "商品上下架", description = "更新商品上下架状态：1-上架，0-下架")
    @PutMapping("/status")
    public Result<Void> updateStatus(@RequestBody @Valid ProductStatusDTO dto) {
        productService.updateStatus(dto);
        return Result.success();
    }

    @Operation(summary = "商品详情", description = "根据ID查询商品详情，优先走Redis缓存，缓存过期时间1小时")
    @GetMapping("/detail")
    public Result<ProductVO> getProductDetail(
            @Parameter(description = "商品ID", required = true, example = "1")
            @RequestParam @NotNull(message = "商品ID不能为空") Long id) {
        ProductVO vo = productService.getProductDetail(id);
        return Result.success(vo);
    }

    @Operation(summary = "商品列表", description = "按关键字/分类/状态分页筛选商品列表")
    @GetMapping("/list")
    public Result<PageResult<ProductVO>> listProducts(
            @Parameter(description = "商品名称关键字") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类") @RequestParam(required = false) String category,
            @Parameter(description = "状态：1-上架 0-下架") @RequestParam(required = false) Integer status,
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，最大100") @RequestParam(defaultValue = "10") long pageSize) {
        PageResult<ProductVO> result = productService.listProducts(keyword, category, status, page, pageSize);
        return Result.success(result);
    }

    @Operation(summary = "库存查询", description = "根据商品ID查询当前库存和是否有货")
    @GetMapping("/stock")
    public Result<ProductStockVO> getProductStock(
            @Parameter(description = "商品ID", required = true, example = "1")
            @RequestParam @NotNull(message = "商品ID不能为空") Long id) {
        ProductStockVO vo = productService.getProductStock(id);
        return Result.success(vo);
    }
}
