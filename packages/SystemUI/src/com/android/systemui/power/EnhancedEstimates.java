package com.android.systemui.power;

import com.android.settingslib.fuelgauge.Estimate;

public interface EnhancedEstimates {

    /**
     * Returns a boolean indicating if the hybrid notification should be used.
     */
    boolean isHybridNotificationEnabled();

    /**
     * Returns an estimate object if the feature is enabled.
     */
    Estimate getEstimate();

    /**
     * Returns a long indicating the amount of time remaining in milliseconds under which we will
     * show a regular warning to the user.
     */
    long getLowWarningThreshold();

    /**
     * Returns a long indicating the amount of time remaining in milliseconds under which we will
     * show a severe warning to the user.
     */
    long getSevereWarningThreshold();

    /**
     * Returns a boolean indicating if the low warning should be shown at all or not.
     */
    boolean getLowWarningEnabled();
}
