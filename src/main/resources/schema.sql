-- 产品表：品牌 + 型号 构成一个可报价的物料
-- remark 承载原「规格」内容（如 12+512G），也可写其它产品备注
CREATE TABLE IF NOT EXISTS product (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    brand       VARCHAR(64)  COMMENT '品牌，可为空',
    model       VARCHAR(128) NOT NULL COMMENT '型号',
    remark      VARCHAR(255) COMMENT '备注/原规格内容',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (brand, model)
);

-- 报价记录表：同一型号可有多次报价；quantity = 当次需求/报价数量
CREATE TABLE IF NOT EXISTS quote (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id      BIGINT         NOT NULL COMMENT '关联产品',
    purchase_price  DECIMAL(12, 2) NOT NULL COMMENT '采购价(CNY)',
    quote_price     DECIMAL(12, 2) NOT NULL COMMENT '报价(CNY)',
    quantity        INT            DEFAULT NULL COMMENT '当次需求数量',
    quote_usd       DECIMAL(12, 2) DEFAULT NULL COMMENT '报价(USD)=报价CNY/汇率',
    quote_total_usd DECIMAL(12, 2) DEFAULT NULL COMMENT '报价总额(USD)=报价USD*数量',
    quote_time      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报价时间',
    remark          VARCHAR(255) COMMENT '备注',
    CONSTRAINT fk_quote_product FOREIGN KEY (product_id) REFERENCES product (id)
);

-- 当日美元汇率：1 USD = usd_cny_used CNY（向下取1位小数，业务计算用）
CREATE TABLE IF NOT EXISTS exchange_rate (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    rate_date     DATE           NOT NULL UNIQUE COMMENT '汇率日期',
    usd_cny_raw   DECIMAL(12, 6) NOT NULL COMMENT '原始汇率',
    usd_cny_used  DECIMAL(10, 1) NOT NULL COMMENT '业务用汇率(向下取1位)',
    fetched_at    TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_quote_product_id ON quote (product_id);
CREATE INDEX IF NOT EXISTS idx_quote_time ON quote (quote_time);

-- 采购订单主表：一单可含多行型号明细
CREATE TABLE IF NOT EXISTS purchase_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(64)  NOT NULL COMMENT '订单号（手填）',
    customer_name VARCHAR(128) NOT NULL COMMENT '客户名称',
    order_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下订时间',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 采购订单明细：一行一个型号；价格列保存时快照
CREATE TABLE IF NOT EXISTS purchase_order_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT         NOT NULL COMMENT '关联订单',
    product_id      BIGINT         COMMENT '关联系统产品，可空',
    model           VARCHAR(128)   NOT NULL COMMENT '型号',
    quantity        INT            NOT NULL DEFAULT 1 COMMENT '数量',
    actual_received DECIMAL(12, 2) COMMENT '实际收款(CNY)，用户填写',
    purchase_amount DECIMAL(12, 2) COMMENT '采购额(CNY)，用户填写',
    sales_usd       DECIMAL(12, 2) COMMENT '销售价USD=最新报价quote_usd',
    exchange_rate   DECIMAL(10, 1) COMMENT '业务汇率=当日usd_cny_used',
    sales_cny       DECIMAL(12, 2) COMMENT '销售价CNY=USD×汇率',
    sales_amount    DECIMAL(12, 2) COMMENT '销售额=销售价CNY×数量',
    profit          DECIMAL(12, 2) COMMENT '利润=销售额-采购额',
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES purchase_order (id)
);

CREATE INDEX IF NOT EXISTS idx_order_no ON purchase_order (order_no);
CREATE INDEX IF NOT EXISTS idx_order_item_order_id ON purchase_order_item (order_id);

-- ===== 老库迁移（幂等） =====
ALTER TABLE product ADD COLUMN IF NOT EXISTS remark VARCHAR(255);
ALTER TABLE product ALTER COLUMN brand VARCHAR(64) NULL;
UPDATE product SET remark = COALESCE(remark, spec) WHERE spec IS NOT NULL;
ALTER TABLE product DROP COLUMN IF EXISTS spec;

ALTER TABLE quote ADD COLUMN IF NOT EXISTS quantity INT;
ALTER TABLE quote ADD COLUMN IF NOT EXISTS quote_usd DECIMAL(12, 2);
ALTER TABLE quote ADD COLUMN IF NOT EXISTS quote_total_usd DECIMAL(12, 2);
