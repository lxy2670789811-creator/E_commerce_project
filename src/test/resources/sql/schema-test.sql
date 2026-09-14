-- 测试库建表脚本：与主 schema.sql 保持一致
-- 由 spring.sql.init 在每次测试上下文启动时执行
--
-- 【为什么是 DROP + CREATE 而不是 IF NOT EXISTS】
-- 原实现用 CREATE TABLE IF NOT EXISTS，导致脚本对"已存在的表"完全无效：
-- 曾经出过 ecommerce_test.orders 缺 idempotency_token 列，脚本里明明写了这一列，
-- 但因为表早就存在，IF NOT EXISTS 直接跳过 → 建表脚本改了、库永远不变 →
-- 并发下单集成测试全部报 Unknown column 'idempotency_token'，排查半天才发现是 schema 漂移。
-- 测试库本来就是一次性的（用例自己 TRUNCATE 全部表），所以这里显式 DROP，
-- 保证"每次上下文启动，库结构与脚本严格一致"，杜绝同类漂移复发。
CREATE DATABASE IF NOT EXISTS ecommerce_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE ecommerce_test;

DROP TABLE IF EXISTS `orders`;
DROP TABLE IF EXISTS `user_address`;
DROP TABLE IF EXISTS `product`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `ai_after_support`;

CREATE TABLE `sys_user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL DEFAULT '$2a$10$43zntPUfCGS3fihhvF9VAew0XnfnXlUU0Xz8d3k0xCdm9IutfyPjC' COMMENT '密码（BCrypt哈希，测试用户明文均为123456）',
    `nickname`    VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `phone`       VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1-正常 0-禁用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE `user_address` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '地址ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `receiver`     VARCHAR(64)  NOT NULL COMMENT '收货人姓名',
    `phone`        VARCHAR(20)  NOT NULL COMMENT '收货人手机号',
    `province`     VARCHAR(64)  NOT NULL COMMENT '省份',
    `city`         VARCHAR(64)  NOT NULL COMMENT '城市',
    `district`     VARCHAR(64)  NOT NULL COMMENT '区县',
    `detail`       VARCHAR(255) NOT NULL COMMENT '详细地址',
    `is_default`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认：1-是 0-否',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户地址表';

CREATE TABLE `product` (
    `id`           BIGINT          NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `name`         VARCHAR(255)    NOT NULL COMMENT '商品名称',
    `description`  TEXT                     DEFAULT NULL COMMENT '商品描述',
    `price`        DECIMAL(10, 2)  NOT NULL COMMENT '商品价格',
    `stock`        INT             NOT NULL DEFAULT 0 COMMENT '库存数量',
    `status`       TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1-上架 0-下架',
    `category`     VARCHAR(64)              DEFAULT NULL COMMENT '分类',
    `image_url`    VARCHAR(500)             DEFAULT NULL COMMENT '商品图片URL',
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_category` (`category`),
    KEY `idx_name` (`name`),
    -- 与 schema.sql 保持一致：商品列表分页查询的联合索引
    KEY `idx_list_query` (`deleted`, `status`, `category`, `create_time`),
    -- 无筛选默认首页流（WHERE deleted=0 ORDER BY create_time DESC）消除 filesort 专用
    KEY `idx_deleted_create` (`deleted`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

CREATE TABLE `orders` (
    `id`              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`        VARCHAR(64)     NOT NULL COMMENT '订单号',
    `user_id`         BIGINT          NOT NULL COMMENT '用户ID',
    `product_id`      BIGINT          NOT NULL COMMENT '商品ID',
    `product_name`    VARCHAR(255)    NOT NULL COMMENT '商品名称（下单时快照）',
    `product_price`   DECIMAL(10, 2)  NOT NULL COMMENT '商品单价（下单时快照）',
    `quantity`        INT             NOT NULL COMMENT '购买数量',
    `total_amount`    DECIMAL(10, 2)  NOT NULL COMMENT '订单总金额',
    `address_id`      BIGINT          NOT NULL COMMENT '收货地址ID',
    `address_snapshot` VARCHAR(1000)  NOT NULL COMMENT '收货地址快照',
    `status`          TINYINT         NOT NULL DEFAULT 0 COMMENT '订单状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消',
    `pay_time`        DATETIME                 DEFAULT NULL COMMENT '支付时间',
    `ship_time`       DATETIME                 DEFAULT NULL COMMENT '发货时间',
    `finish_time`     DATETIME                 DEFAULT NULL COMMENT '完成时间',
    `cancel_time`     DATETIME                 DEFAULT NULL COMMENT '取消时间',
    `cancel_reason`   VARCHAR(500)             DEFAULT NULL COMMENT '取消原因',
    `idempotency_token` VARCHAR(64)           DEFAULT NULL COMMENT '下单幂等凭证token（一次性，唯一索引兜底去重）',
    `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_idempotency_token` (`idempotency_token`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`),
    -- 定时扫描超时未支付订单的复合索引（MQ 不可用时的兜底补偿通道），与主 schema 保持一致
    KEY `idx_status_create_time` (`status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

CREATE TABLE `ai_after_support` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `order_id`      BIGINT       NOT NULL COMMENT '订单ID',
    `order_no`      VARCHAR(64)  NOT NULL COMMENT '订单号',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `user_input`    TEXT         NOT NULL COMMENT '用户反馈文本',
    `ai_result`     JSON                  DEFAULT NULL COMMENT '大模型返回结果（JSON）',
    `problem_type`  VARCHAR(32)           DEFAULT NULL COMMENT '问题分类：质量问题/物流问题/咨询/其他',
    `emotion`       VARCHAR(16)           DEFAULT NULL COMMENT '情绪：负面/中性/正面',
    `suggestion`    VARCHAR(500)          DEFAULT NULL COMMENT '建议处理方案',
    `ai_status`     TINYINT      NOT NULL DEFAULT 1 COMMENT 'AI处理状态：1-成功 0-失败待人工',
    `fail_reason`   VARCHAR(500)          DEFAULT NULL COMMENT 'AI失败原因',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI售后分析记录表';