# 型号报价管理系统（quote-manager）

本地单机版：产品型号报价 + 采购订单。Spring Boot 3 + MyBatis-Plus + H2，数据存在本地文件，不需要装数据库。

## 快速启动

```bash
cd "/Users/mr.zhang/code project/quote-manager"

# 若改过代码，先打包
export PATH="$HOME/tools/apache-maven-3.9.9/bin:$HOME/tools/jdk-21.0.2/Contents/Home/bin:$PATH"
mvn -DskipTests package

# 启动（后台）
nohup java -jar target/quote-manager-0.1.0.jar > /tmp/quote-manager.log 2>&1 &

# 浏览器打开
# http://localhost:8080/
```

停止服务：

```bash
pkill -f quote-manager-0.1.0.jar
```

日志：`/tmp/quote-manager.log`  
数据：`data/quote-db.mv.db`（H2 文件库，**重要数据请自行备份 `data/` 目录**）

运行测试：

```bash
mvn test
```

## 功能一览

### 产品报价
- 产品：品牌可空、型号必填、备注；同品牌同型号不可重复
- 报价历史：采购价、报价、数量、报价时间、备注；列表显示最新一条
- 编辑产品会就地更新「最新那条」报价（不新增历史）
- 批量删除、批量导出 Excel（先预览再导出，含总金额行）
- Excel 导入：列序 `Brand | Model | Num | Purchase price | Quote price | Price(USD/pcs) | Total | noted`

### 采购订单
- 订单列表：订单号、客户名称、下订时间、实际收款额、采购额、实际总利润
- 订单详情：按型号多行明细；添加型号时**输入搜索**（不用下拉）
  - 库内已有 → 自动带上最新报价
  - 库内没有 → 自动建档，并提示「该型号还未进行报价」
- 实际收款是**整单一笔**，不按型号拆

## 金额口径（务必按此填写）

| 字段 | 规则 |
|---|---|
| 汇率 | 当日业务汇率 = 原始汇率**向下取 1 位**（如 6.693661 → 6.6） |
| 报价(USD) | 报价(CNY) ÷ 业务汇率 |
| 销售价(USD) | 该型号**最新报价**的 `quote_usd` |
| 销售价(CNY) | 销售价(USD) × 汇率 |
| 销售额 | 销售价(CNY) × 数量 |
| 利润 | 销售额 − 采购额 |
| 实际总利润 | 实际收款 − Σ 明细采购额 |

约定：
- **采购额按正数成本填写**（不要填负数）
- 报价 / 汇率 USD 金额落库快照，后续改报价**不影响**历史订单
- 订单号手动填写，不可重复

## 技术栈

- Java 21 · Spring Boot 3.2.5 · MyBatis-Plus 3.5.5
- H2（文件库）· Apache POI 5.2.5（Excel）
- 前端：原生 HTML/CSS/JS（无框架），静态资源在 `src/main/resources/static/`

## 目录结构

```
quote-manager/
├── README.md
├── pom.xml
├── data/                          # H2 数据文件（已 gitignore，注意备份）
└── src/main/
    ├── java/com/quote/manager/
    │   ├── QuoteManagerApplication.java
    │   ├── config/                # MyBatis-Plus 分页、启动回填 USD
    │   ├── controller/            # Product / Quote / ExchangeRate / PurchaseOrder
    │   ├── service/               # 业务与计算规则
    │   ├── mapper/ entity/ dto/
    │   └── common/                # ApiResponse、BizException、全局异常
    └── resources/
        ├── application.yml
        ├── schema.sql             # 建表 + 幂等迁移
        └── static/
            ├── index.html         # 页面结构（主/采购两视图 + 弹窗）
            ├── css/app.css
            └── js/
                ├── common.js      # api / toast / 时间格式 / 汇率 / 视图切换
                ├── products.js    # 产品列表、导入导出、批量删除、报价历史
                ├── orders.js      # 采购订单列表、详情、型号明细
                └── init.js        # 页面初始化
```

## 常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/products` | 产品分页（含最新报价） |
| POST | `/api/products/import` | Excel 导入 |
| POST | `/api/products/export-excel` | 按 id 导出 Excel |
| POST | `/api/products/batch-delete` | 批量删除产品 |
| GET | `/api/rates/today` | 今日美元汇率 |
| GET | `/api/orders` | 订单分页 |
| GET | `/api/orders/{id}` | 订单详情（含明细计算列） |
| POST | `/api/orders` | 新建订单 + 明细 |

## 提交风格

```
feat: 中文简述
fix:  中文简述
```

建议小步提交，一个可验收功能一个 commit。
