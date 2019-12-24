package com.android.systemui.power;

import com.android.settingslib.fuelgauge.Estimate;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class EnhancedEstimatesImpl implements EnhancedEstimates {

    @Inject
    public EnhancedEstimatesImpl() {
    }

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
