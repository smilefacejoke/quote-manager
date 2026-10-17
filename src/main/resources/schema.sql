-- 产品表：品牌 + 型号 构成一个可报价的物料
CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand       VARCHAR(64)  NOT NULL COMMENT '品牌',
    model       VARCHAR(128) NOT NULL COMMENT '型号',
    spec        VARCHAR(255) COMMENT '规格/描述',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (brand, model)
);

-- 报价记录表：同一型号可有多次报价
CREATE TABLE IF NOT EXISTS quote (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT         NOT NULL COMMENT '关联产品',
    purchase_price  DECIMAL(12, 2) NOT NULL COMMENT '采购价',
    quote_price     DECIMAL(12, 2) NOT NULL COMMENT '报价',
    quote_time      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报价时间',
    remark          VARCHAR(255) COMMENT '备注',
    CONSTRAINT fk_quote_product FOREIGN KEY (product_id) REFERENCES product (id)
);

CREATE INDEX IF NOT EXISTS idx_quote_product_id ON quote (product_id);
CREATE INDEX IF NOT EXISTS idx_quote_time ON quote (quote_time);
