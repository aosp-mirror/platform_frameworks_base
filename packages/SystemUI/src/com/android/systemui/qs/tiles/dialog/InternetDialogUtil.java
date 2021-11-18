package com.android.systemui.qs.tiles.dialog;

import android.content.Context;
import android.util.FeatureFlagUtils;

public class InternetDialogUtil {

    public static boolean isProviderModelEnabled(Context context) {
        if (context == null) {
            return false;
        }
        return FeatureFlagUtils.isEnabled(context, FeatureFlagUtils.SETTINGS_PROVIDER_MODEL);
    }
}
