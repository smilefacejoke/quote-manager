# 型号报价管理系统

本地开发用的轻量报价管理：产品（品牌/型号）+ 多次报价记录（采购价/报价/报价时间）。

## 技术栈

- Java 21 + Spring Boot 3.2
- MyBatis-Plus
- H2 本地文件库（`./data/quote-db`）
- 原生 HTML/JS 管理界面

## 启动

```bash
export JAVA_HOME=/Users/mr.zhang/tools/jdk-21.0.2.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/Users/mr.zhang/tools/apache-maven-3.9.9/bin:$PATH"

cd quote-manager
mvn spring-boot:run
```

- 管理界面：http://localhost:8080/
- H2 控制台：http://localhost:8080/h2-console（JDBC URL: `jdbc:h2:file:./data/quote-db`，用户 `sa`，密码空）

## 数据模型

| 表 | 字段 | 说明 |
|---|---|---|
| product | brand, model, spec, created_at | 品牌+型号唯一 |
| quote | product_id, purchase_price, quote_price, quote_time, remark | 同一产品可多次报价 |

## 业务规则

- 价格必须 ≥ 0（`DECIMAL`，不用 `FLOAT`）
- 采购价 ≤ 报价
- 同品牌+型号不可重复

## API 速查

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/products?pageNo&pageSize&brand&model` | 产品分页查询 |
| POST | `/api/products` | 新增产品 |
| PUT | `/api/products/{id}` | 更新产品 |
| DELETE | `/api/products/{id}` | 删除产品 |
| GET | `/api/quotes?pageNo&pageSize&productId` | 报价分页查询 |
| POST | `/api/quotes` | 新增报价 |
| PUT | `/api/quotes/{id}` | 更新报价 |
| DELETE | `/api/quotes/{id}` | 删除报价 |

## Vibe Coding 练习建议

1. 每改一个功能就 `git commit`，崩了好回退
2. 把需求写成验收标准再交给 AI，例如：「产品列表支持按品牌模糊查询」
3. 报错时贴完整堆栈，不要自己转述
