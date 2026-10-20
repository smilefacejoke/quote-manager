-- 产品表：品牌 + 型号 构成一个可报价的物料
-- remark 承载原「规格」内容（如 12+512G），也可写其它产品备注
CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand       VARCHAR(64)  NOT NULL COMMENT '品牌',
    model       VARCHAR(128) NOT NULL COMMENT '型号',
    remark      VARCHAR(255) COMMENT '备注/原规格内容',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (brand, model)
);

-- 报价记录表：同一型号可有多次报价；quantity = 当次需求/报价数量
CREATE TABLE IF NOT EXISTS quote (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT         NOT NULL COMMENT '关联产品',
    purchase_price  DECIMAL(12, 2) NOT NULL COMMENT '采购价',
    quote_price     DECIMAL(12, 2) NOT NULL COMMENT '报价',
    quantity        INT            DEFAULT NULL COMMENT '当次需求数量',
    quote_time      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报价时间',
    remark          VARCHAR(255) COMMENT '备注',
    CONSTRAINT fk_quote_product FOREIGN KEY (product_id) REFERENCES product (id)
);

CREATE INDEX IF NOT EXISTS idx_quote_product_id ON quote (product_id);
CREATE INDEX IF NOT EXISTS idx_quote_time ON quote (quote_time);

-- ===== 老库迁移（幂等） =====
-- product.spec → product.remark
ALTER TABLE product ADD COLUMN IF NOT EXISTS remark VARCHAR(255);
UPDATE product SET remark = COALESCE(remark, spec) WHERE spec IS NOT NULL;
ALTER TABLE product DROP COLUMN IF EXISTS spec;

-- quote 增加数量列
ALTER TABLE quote ADD COLUMN IF NOT EXISTS quantity INT;
