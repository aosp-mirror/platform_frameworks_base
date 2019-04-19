package com.android.systemui.power;

import com.android.settingslib.fuelgauge.Estimate;

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

    @Override
    public boolean getLowWarningEnabled() {
        return true;
    }
}
