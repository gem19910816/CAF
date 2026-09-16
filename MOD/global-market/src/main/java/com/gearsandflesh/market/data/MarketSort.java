package com.gearsandflesh.market.data;

public enum MarketSort {
    NEWEST("最新上架"),
    PRICE_ASC("价格从低到高"),
    PRICE_DESC("价格从高到低"),
    OLDEST("即将到期");

    private final String displayName;

    MarketSort(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
