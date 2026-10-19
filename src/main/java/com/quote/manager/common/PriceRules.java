package com.quote.manager.common;

import java.math.BigDecimal;

/**
 * 报价相关业务规则。
 * 只有业务方清楚的约束写在这里，Service 共用，避免两处各写一遍。
 */
public final class PriceRules {

    private PriceRules() {
    }

    /** 报价不能低于采购价，否则就是亏本报价 */
    public static void validateNotBelowCost(BigDecimal purchasePrice, BigDecimal quotePrice) {
        if (purchasePrice.compareTo(quotePrice) > 0) {
            throw new BizException("业务规则：采购价不能大于报价（采购价=" + purchasePrice + "，报价=" + quotePrice + "）");
        }
    }

    /** 录入报价时价格必须成对出现（都必填或都为空） */
    public static void validatePair(BigDecimal purchasePrice, BigDecimal quotePrice) {
        if ((purchasePrice == null) != (quotePrice == null)) {
            throw new BizException("采购价和报价需要一起填写，或都留空");
        }
    }
}
