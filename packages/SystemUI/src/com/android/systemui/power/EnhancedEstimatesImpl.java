package com.android.systemui.power;

import android.util.Log;

public class EnhancedEstimatesImpl implements EnhancedEstimates {

    @Override
    public boolean isHybridNotificationEnabled() {
        return false;
    }

    @Override
    public Estimate getEstimate() {
        return null;
    }

    @Override
    public long getLowWarningThreshold() {
        return 0;
    }

    @Override
    public long getSevereWarningThreshold() {
        return 0;
    }
}
