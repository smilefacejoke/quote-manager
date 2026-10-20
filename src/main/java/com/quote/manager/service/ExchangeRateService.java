package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quote.manager.common.BizException;
import com.quote.manager.entity.ExchangeRate;
import com.quote.manager.mapper.ExchangeRateMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 当日美元汇率（1 USD = x CNY）。
 * 每天只取一次，业务口径：原始汇率向下保留 1 位小数（不四舍五入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private static final String API_PRIMARY = "https://open.er-api.com/v6/latest/USD";
    private static final String API_FALLBACK =
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.json";

    private final ExchangeRateMapper exchangeRateMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 今日业务汇率；库里没有则拉接口，失败则报错（避免用错汇率算钱） */
    public BigDecimal getTodayUsedRate() {
        return getToday().getUsdCnyUsed();
    }

    public ExchangeRate getToday() {
        LocalDate today = LocalDate.now();
        ExchangeRate cached = findByDate(today);
        if (cached != null) {
            return cached;
        }
        return fetchAndSave(today);
    }

    private ExchangeRate findByDate(LocalDate date) {
        return exchangeRateMapper.selectOne(new LambdaQueryWrapper<ExchangeRate>()
                .eq(ExchangeRate::getRateDate, date));
    }

    /**
     * 业务汇率 = 原始汇率向下取 1 位小数。
     * 例：6.693661 → 6.6（不是四舍五入的 6.7）
     */
    public static BigDecimal floorOneDecimal(BigDecimal raw) {
        return raw.setScale(1, RoundingMode.DOWN);
    }

    /**
     * 界面展示用精确汇率：3 位小数、四舍五入。
     * 与「计算用」的向下取 1 位业务汇率区分开。
     */
    public static BigDecimal displayRate(BigDecimal raw) {
        return raw.setScale(3, RoundingMode.HALF_UP);
    }

    /** CNY → USD：用业务汇率换算，金额保留 2 位小数 */
    public static BigDecimal cnyToUsd(BigDecimal cny, BigDecimal usedRate) {
        if (cny == null || usedRate == null || usedRate.signum() == 0) {
            return null;
        }
        return cny.divide(usedRate, 2, RoundingMode.HALF_UP);
    }

    private ExchangeRate fetchAndSave(LocalDate date) {
        BigDecimal raw = fetchFromApi();
        ExchangeRate rate = new ExchangeRate();
        rate.setRateDate(date);
        rate.setUsdCnyRaw(raw);
        rate.setUsdCnyUsed(floorOneDecimal(raw));
        rate.setFetchedAt(LocalDateTime.now());
        exchangeRateMapper.insert(rate);
        log.info("刷新汇率 {} raw={} used={}", date, raw, rate.getUsdCnyUsed());
        return rate;
    }

    private BigDecimal fetchFromApi() {
        try {
            BigDecimal fromPrimary = parsePrimary(fetchJson(API_PRIMARY));
            if (fromPrimary != null) {
                return fromPrimary;
            }
        } catch (Exception e) {
            log.warn("主汇率接口失败: {}", e.getMessage());
        }
        try {
            BigDecimal fromFallback = parseFallback(fetchJson(API_FALLBACK));
            if (fromFallback != null) {
                return fromFallback;
            }
        } catch (Exception e) {
            log.warn("备用汇率接口失败: {}", e.getMessage());
        }
        throw new BizException("今日美元汇率获取失败，请检查网络后重试");
    }

    private String fetchJson(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** open.er-api.com: { rates: { CNY: 6.69... } } */
    private BigDecimal parsePrimary(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        JsonNode cny = node.path("rates").path("CNY");
        return cny.isMissingNode() ? null : cny.decimalValue();
    }

    /** jsdelivr currency-api: { usd: { cny: 6.68... } } */
    private BigDecimal parseFallback(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        JsonNode cny = node.path("usd").path("cny");
        return cny.isMissingNode() ? null : cny.decimalValue();
    }
}
