package com.ecommerce.controller;

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
 * 用户模块 Controller
 */
@Tag(name = "用户模块", description = "登录（签发 JWT）、收货地址管理")
@Validated
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserAddressService userAddressService;

    @Operation(summary = "登录", description = "BCrypt 校验密码，成功后签发 JWT。测试用户：test001/123456。"
            + "前端需将返回的 token 放在后续请求的 Authorization: Bearer <token> 头中")
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody @Valid UserLoginDTO dto) {
        String token = userService.login(dto.getUsername(), dto.getPassword());
        UserDO user = userService.getUserByUsername(dto.getUsername());
        UserLoginVO vo = UserLoginVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .token(token)
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
