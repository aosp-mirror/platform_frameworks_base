/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.fuelgauge;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings.Secure;
import android.util.Log;

/**
 * Utilities related to battery saver.
 */
public class BatterySaverUtils {
    private static final String TAG = "BatterySaverUtils";

    private BatterySaverUtils() {
    }

    private static final boolean DEBUG = false;

    // Broadcast action for SystemUI to show the battery saver confirmation dialog.
    public static final String ACTION_SHOW_START_SAVER_CONFIRMATION = "PNW.startSaverConfirmation";

    /**
     * Enable / disable battery saver by user request.
     * - If it's the first time and needFirstTimeWarning, show the first time dialog.
     * - If it's 4th time through 8th time, show the schedule suggestion notification.
     *
     * @param enable true to disable battery saver.
     *
     * @return true if the request succeeded.
     */
    public static synchronized boolean setPowerSaveMode(Context context,
            boolean enable, boolean needFirstTimeWarning) {
        if (DEBUG) {
            Log.d(TAG, "Battery saver turning " + (enable ? "ON" : "OFF"));
        }
        final ContentResolver cr = context.getContentResolver();

        if (enable && needFirstTimeWarning && maybeShowBatterySaverConfirmation(context)) {
            return false;
        }
        if (enable && !needFirstTimeWarning) {
            setBatterySaverConfirmationAcknowledged(context);
        }

        if (context.getSystemService(PowerManager.class).setPowerSaveMode(enable)) {
            if (enable) {
                Secure.putInt(cr, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT,
                        Secure.getInt(cr, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 0) + 1);

                // TODO If enabling, and the count is between 4 and 8 (inclusive), then
                // show the "battery saver schedule suggestion" notification.
            }

            return true;
        }
        return false;
    }

    private static boolean maybeShowBatterySaverConfirmation(Context context) {
        if (Secure.getInt(context.getContentResolver(),
                Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 0) != 0) {
            return false; // Already shown.
        }
        final Intent i = new Intent(ACTION_SHOW_START_SAVER_CONFIRMATION);
        context.sendBroadcast(i);

        return true;
    }

    private static void setBatterySaverConfirmationAcknowledged(Context context) {
        Secure.putInt(context.getContentResolver(), Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
    }
}
