package com.ecommerce.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.entity.UserDO;
import com.ecommerce.mapper.UserMapper;
import com.ecommerce.security.JwtProperties;
import com.ecommerce.security.JwtTokenService;
import com.ecommerce.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户 Service 实现类（密码 BCrypt 哈希存储 + 登录签发 JWT）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    @Override
    public String login(String username, String password) {
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDO::getUsername, username);
        UserDO user = this.getOne(wrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_LOGIN_FAILED);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_LOGIN_FAILED, "用户已被禁用");
        }
        // 校验密码（BCrypt）。兼容存量明文：数据库里若仍是明文，允许比对并自动升级为哈希，
        // 避免改密后老账号全部登不上（由 ecommerce.jwt.allow-plain-text-login 控制，生产建议关闭）
        verifyPassword(user, password);
        log.info("用户登录成功：userId={}, username={}", user.getId(), username);
        // 签发真 JWT，返回给前端；前端后续请求在 Authorization 头携带
        return jwtTokenService.generate(user.getId());
    }

    /**
     * BCrypt 校验密码；若存储的是明文且允许明文过渡，则比对通过后原地升级为 BCrypt 哈希。
     */
    private void verifyPassword(UserDO user, String rawPassword) {
        String stored = user.getPassword();
        // BCrypt 哈希以 $2a$/$2b$ 开头
        boolean isBcrypt = stored != null && stored.startsWith("$2");
        if (isBcrypt) {
            if (!BCrypt.checkpw(rawPassword, stored)) {
                throw new BusinessException(ErrorCode.USER_LOGIN_FAILED);
            }
            return;
        }
        // 存量明文：仅在开关开启时允许并升级
        if (jwtProperties.isAllowPlainTextLogin() && rawPassword.equals(stored)) {
            String hashed = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
            this.update(new LambdaUpdateWrapper<UserDO>()
                    .eq(UserDO::getId, user.getId())
                    .set(UserDO::getPassword, hashed));
            log.info("存量明文密码已自动升级为 BCrypt：userId={}", user.getId());
            return;
        }
        throw new BusinessException(ErrorCode.USER_LOGIN_FAILED);
    }

    @Override
    public UserDO getUserByUsername(String username) {
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDO::getUsername, username);
        UserDO user = this.getOne(wrapper);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    @Override
    public UserDO getUserById(Long id) {
        UserDO user = this.getById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}
