package com.android.systemui.power;

import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.fuelgauge.EstimateKt;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

@SysUISingleton
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
        // Returns an unknown estimate.
        return new Estimate(EstimateKt.ESTIMATE_MILLIS_UNKNOWN,
                false /* isBasedOnUsage */,
                EstimateKt.AVERAGE_TIME_TO_DISCHARGE_UNKNOWN);
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
