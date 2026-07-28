package com.example.stockticker;

/**
 * A single stock/futures quote snapshot: current price, absolute change, percent
 * change since previous close, and the epoch-millis timestamp the data is from
 * (as reported by the provider, not "when we fetched it").
 */
public record Quote(double price, double change, double percentChange, long timestampMillis) {}
