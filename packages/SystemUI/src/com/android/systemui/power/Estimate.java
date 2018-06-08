package com.android.systemui.power;

public class Estimate {
    public final long estimateMillis;
    public final boolean isBasedOnUsage;

    public Estimate(long estimateMillis, boolean isBasedOnUsage) {
        this.estimateMillis = estimateMillis;
        this.isBasedOnUsage = isBasedOnUsage;
    }
}
