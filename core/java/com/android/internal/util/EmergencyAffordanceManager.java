/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.internal.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;

/**
 * A class that manages emergency affordances and enables immediate calling to emergency services
 */
public class EmergencyAffordanceManager {

    public static final boolean ENABLED = true;

    /**
     * Global setting override with the number to call with the emergency affordance.
     * @hide
     */
    private static final String EMERGENCY_CALL_NUMBER_SETTING = "emergency_affordance_number";

    /**
     * Global setting, whether the emergency affordance should be shown regardless of device state.
     * The value is a boolean (1 or 0).
     * @hide
     */
    private static final String FORCE_EMERGENCY_AFFORDANCE_SETTING = "force_emergency_affordance";

    private final Context mContext;

    public EmergencyAffordanceManager(Context context) {
        mContext = context;
    }

    /**
     * perform an emergency call.
     */
    public final void performEmergencyCall() {
        performEmergencyCall(mContext);
    }

    private static Uri getPhoneUri(Context context) {
        String number = context.getResources().getString(
                com.android.internal.R.string.config_emergency_call_number);
        if (Build.IS_DEBUGGABLE) {
            String override = Settings.Global.getString(
                    context.getContentResolver(), EMERGENCY_CALL_NUMBER_SETTING);
            if (override != null) {
                number = override;
            }
        }
        return Uri.fromParts("tel", number, null);
    }

    private static void performEmergencyCall(Context context) {
        Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY);
        intent.setData(getPhoneUri(context));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    /**
     * @return whether emergency affordance should be active.
     */
    public boolean needsEmergencyAffordance() {
        if (!ENABLED) {
            return false;
        }
        if (forceShowing()) {
            return true;
        }
        return isEmergencyAffordanceNeeded();
    }

    private boolean isEmergencyAffordanceNeeded() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.EMERGENCY_AFFORDANCE_NEEDED, 0) != 0;
    }


    private boolean forceShowing() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                FORCE_EMERGENCY_AFFORDANCE_SETTING, 0) != 0;
    }
}
