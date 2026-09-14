-- ============================================================
-- 迁移脚本 V20260914_01：product 表补齐商品列表查询索引
-- ============================================================
-- 背景：
--   `schema.sql` 里早已定义 idx_list_query，但它从未落到任何已存在的库上
--   （schema.sql 只在 MySQL 容器首次初始化空数据卷时被执行，且是 DROP TABLE 破坏性重建）。
--   2026-09-14 核查发现 ecommerce / ecommerce_test 两个库的 product 表都缺这条索引，
--   故补本脚本，并顺带补一条真正能消除默认首页流 filesort 的 idx_deleted_create。
--
-- 补的是什么：
--   1) idx_list_query(deleted, status, category, create_time)
--      带筛选的列表查询：WHERE deleted=0 AND status=? AND category=? ORDER BY create_time DESC
--      —— 三个等值列在前、排序列在后，命中后走 Backward index scan，无 filesort。
--      生效前提是这三列都要给等值条件；缺任何一列（尤其是默认首页流只给 deleted=0）
--      都会因“跳过中间列”而用不上索引顺序，退化为 filesort。
--   2) idx_deleted_create(deleted, create_time)
--      无筛选默认首页流：WHERE deleted=0 ORDER BY create_time DESC
--      —— 实测这是唯一能让该形状消除 filesort 的索引，补 idx_list_query 覆盖不到的场景。
--
-- 执行方式（对每个需要升级的库各跑一次）：
--   mysql --default-character-set=utf8mb4 -uroot -p ecommerce      < V20260914_01__add_product_list_index.sql
--   mysql --default-character-set=utf8mb4 -uroot -p ecommerce_test < V20260914_01__add_product_list_index.sql
--
--   ⚠️ 务必带 --default-character-set=utf8mb4：Windows 版 mysql 客户端默认
--      character_set_client=gbk，会把 UTF-8 脚本里的中文按 GBK 误解码，并让 CONCAT 之类的
--      字符串函数报 "Illegal mix of collations"。
--
-- 安全性：MySQL 8 的 ADD KEY 走 INPLACE 在线 DDL，普通规模表秒级完成，不锁表。
-- 幂等性：先查 information_schema，索引已存在则跳过，重复执行安全。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- idx_list_query ----------
SET @has_list_query := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'product'
      AND index_name   = 'idx_list_query'
);
SET @sql := IF(@has_list_query = 0,
    'ALTER TABLE `product` ADD KEY `idx_list_query` (`deleted`, `status`, `category`, `create_time`)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- idx_deleted_create ----------
SET @has_deleted_create := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name   = 'product'
      AND index_name   = 'idx_deleted_create'
);
SET @sql := IF(@has_deleted_create = 0,
    'ALTER TABLE `product` ADD KEY `idx_deleted_create` (`deleted`, `create_time`)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============================================================
-- 验证：应能看到 idx_list_query（4 列）与 idx_deleted_create（2 列）
-- ============================================================
SHOW INDEX FROM `product`;

-- ============================================================
-- 附：执行计划验证（手工跑，期望 Extra 为 Backward index scan，不应有 Using filesort）
-- ============================================================
-- -- 无筛选默认首页流 -> 应走 idx_deleted_create
-- EXPLAIN SELECT id, name, price, stock, status, category, create_time
--   FROM product WHERE deleted = 0 ORDER BY create_time DESC LIMIT 0, 20;
--
-- -- 带筛选的列表查询 -> 应走 idx_list_query
-- EXPLAIN SELECT id, name, price, stock, status, category, create_time
--   FROM product WHERE deleted = 0 AND status = 1 AND category = '数码配件'
--   ORDER BY create_time DESC LIMIT 0, 20;
