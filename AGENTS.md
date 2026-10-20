# AGENTS.md

Single-module Spring Boot app (型号报价管理系统): products (brand/model) + multi-entry quote history + USD conversion.
Stack: Java 21, Spring Boot 3.2.5, MyBatis-Plus, H2 file DB (`./data/quote-db`), single-file frontend (`src/main/resources/static/index.html`, no build step).

## Commands

No Maven wrapper — use system Maven. Toolchain is pinned in README:

```bash
export JAVA_HOME=/Users/mr.zhang/tools/jdk-21.0.2.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/Users/mr.zhang/tools/apache-maven-3.9.9/bin:$PATH"

mvn spring-boot:run          # app: http://localhost:8080  (H2 console: /h2-console)
mvn test                     # whole suite
mvn test -Dtest=QuoteServiceRuleTest   # single test class
```

There is no lint/typecheck/formatter config and no CI. `mvn test` is the only verification gate.

## Read the code, not the README

README.md is partially stale (still describes the old `spec` column, omits exchange-rate/USD features and endpoints).
Trust `src/main/resources/schema.sql`, `application.yml`, and the controllers.

Endpoints the README misses: `GET /api/products/brands`, `GET /api/products/export` (CSV with UTF-8 BOM), `GET /api/rates/today`, `POST /api/rates/backfill-usd`.

## Gotchas

- **Lombok must stay ≥ 1.18.32** (pinned 1.18.34 in `pom.xml`). Older Lombok emits invalid bytecode (`Code length 0`) on JDK 21. Don't downgrade or remove the pin.
- **`schema.sql` runs on every boot** (`spring.sql.init.mode: always`) with `continue-on-error: true`. Its legacy migration block references the already-dropped `spec` column, so those statements *are expected to fail* on subsequent startups. Do not "fix" those errors.
- **Tests are `@SpringBootTest` against the real file DB** (`./data/quote-db`, gitignored but live) — they write real rows. No test profile / in-memory DB.
- **Tests and quote writes need a cached exchange rate or network.** Any quote save computes USD fields via `ExchangeRateService.getTodayUsedRate()`; if today's rate isn't in `exchange_rate` yet, it fetches from external APIs (open.er-api.com, fallback jsdelivr) and throws `BizException("今日美元汇率获取失败…")` when offline.
- **Business FX rate is the raw rate FLOORED to 1 decimal** (`RoundingMode.DOWN`, e.g. 6.693661 → 6.6), *not* rounded. Display rate is separate (3 decimals, HALF_UP). Amounts use `BigDecimal` only — prices are `DECIMAL`, never `float`/`double`.
- Quote USD columns (`quote_usd`, `quote_total_usd = quote_usd × quantity`) are derived on every write; total is null when quantity is null. `UsdBackfillRunner` backfills null `quote_usd` at startup (idempotent, swallows failures).

## Business rules (enforced in `common/PriceRules.java`)

- purchase price ≤ quote price (equal allowed).
- On product create/edit, prices must be filled as a pair (both or neither).
- brand + model is unique (DB UNIQUE + app check in `ProductService.checkDuplicate`).
- Exception messages are Chinese and `QuoteServiceRuleTest` asserts on exact substrings (e.g. `"采购价不能大于报价"`) — don't reword them casually.

## Product vs quote writes (easy to get wrong)

- Product create with prices → inserts the first quote (remark `建档报价`) in the same transaction.
- Product update with price/quantity → **mutates the latest quote in place** (does not add history); sets `quote_time` to now unless provided. Only `POST /api/quotes` appends history.
- Product delete cascades its quotes in one transaction — keep it that way to avoid orphans.
- "Latest quote" tie-break is `quote_time` desc, then `id` desc.

## Layout

`com.quote.manager` — `entity` / `mapper` (MyBatis-Plus `BaseMapper`, no XML) / `service` / `controller` / `dto` / `common` (ApiResponse, BizException, GlobalExceptionHandler, PriceRules) / `config`.
Package boundaries are flat and small; the only non-obvious wiring is `UsdBackfillRunner` (CommandLineRunner) and `MybatisPlusConfig`.
