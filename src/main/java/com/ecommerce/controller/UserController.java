package com.ecommerce.controller;

import cn.hutool.core.util.IdUtil;
import com.ecommerce.common.Result;
import com.ecommerce.convert.UserAddressConvert;
import com.ecommerce.dto.user.UserAddressAddDTO;
import com.ecommerce.dto.user.UserLoginDTO;
import com.ecommerce.entity.UserAddressDO;
import com.ecommerce.entity.UserDO;
import com.ecommerce.service.UserAddressService;
import com.ecommerce.service.UserService;
import com.ecommerce.vo.user.UserAddressVO;
import com.ecommerce.vo.user.UserLoginVO;
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
 * 用户模块 Controller（极简实现）
 */
@Tag(name = "用户模块", description = "模拟登录、收货地址管理（极简实现，重点在订单和商品）")
@Validated
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserAddressService userAddressService;

    @Operation(summary = "模拟登录", description = "极简实现：用户名+密码校验，返回模拟Token和用户信息。测试用户：test001/123456")
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO dto) {
        Long userId = userService.login(dto.getUsername(), dto.getPassword());
        UserDO user = userService.getUserById(userId);
        UserLoginVO vo = UserLoginVO.builder()
                .userId(userId)
                .username(user.getUsername())
                .nickname(user.getNickname())
                .token("mock-token-" + IdUtil.fastSimpleUUID().substring(0, 16))
                .build();
        return Result.success(vo);
    }

    @Operation(summary = "查询地址列表", description = "查询指定用户的所有收货地址，默认地址在前")
    @GetMapping("/address/list")
    public Result<List<UserAddressVO>> listAddress(
            @Parameter(description = "用户ID", required = true, example = "1")
            @RequestParam @NotNull(message = "用户ID不能为空") Long userId) {
        List<UserAddressDO> list = userAddressService.listByUserId(userId);
        return Result.success(UserAddressConvert.INSTANCE.doListToVOList(list));
    }

    @Operation(summary = "新增收货地址", description = "为指定用户添加一个新的收货地址，设为默认时自动取消其他默认")
    @PostMapping("/address/add")
    public Result<Long> addAddress(@RequestBody @Valid UserAddressAddDTO dto) {
        UserAddressDO address = UserAddressConvert.INSTANCE.addDTOToDO(dto);
        if (address.getIsDefault() == null) {
            address.setIsDefault(0);
        }
        Long addressId = userAddressService.addAddress(address);
        return Result.success(addressId);
    }
}
