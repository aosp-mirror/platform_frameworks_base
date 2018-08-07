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
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.util.KeyValueListParser;
import android.util.Log;
import android.util.Slog;

/**
 * Utilities related to battery saver.
 */
public class BatterySaverUtils {
    private static final String TAG = "BatterySaverUtils";

    private BatterySaverUtils() {
    }

    private static final boolean DEBUG = false;

    private static final String SYSUI_PACKAGE = "com.android.systemui";

    /** Broadcast action for SystemUI to show the battery saver confirmation dialog. */
    public static final String ACTION_SHOW_START_SAVER_CONFIRMATION = "PNW.startSaverConfirmation";

    /**
     * Broadcast action for SystemUI to show the notification that suggests turning on
     * automatic battery saver.
     */
    public static final String ACTION_SHOW_AUTO_SAVER_SUGGESTION
            = "PNW.autoSaverSuggestion";

    private static class Parameters {
        private final Context mContext;

        /**
         * We show the auto battery saver suggestion notification when the user manually enables
         * battery saver for the START_NTH time through the END_NTH time.
         * (We won't show it for END_NTH + 1 time and after.)
         */
        private static final int AUTO_SAVER_SUGGESTION_START_NTH = 4;
        private static final int AUTO_SAVER_SUGGESTION_END_NTH = 8;

        public final int startNth;
        public final int endNth;

        public Parameters(Context context) {
            mContext = context;

            final String newValue = Global.getString(mContext.getContentResolver(),
                    Global.LOW_POWER_MODE_SUGGESTION_PARAMS);
            final KeyValueListParser parser = new KeyValueListParser(',');
            try {
                parser.setString(newValue);
            } catch (IllegalArgumentException e) {
                Slog.wtf(TAG, "Bad constants: " + newValue);
            }
            startNth = parser.getInt("start_nth", AUTO_SAVER_SUGGESTION_START_NTH);
            endNth = parser.getInt("end_nth", AUTO_SAVER_SUGGESTION_END_NTH);
        }
    }

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
                final int count =
                        Secure.getInt(cr, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, 0) + 1;
                Secure.putInt(cr, Secure.LOW_POWER_MANUAL_ACTIVATION_COUNT, count);

                final Parameters parameters = new Parameters(context);

                if ((count >= parameters.startNth)
                        && (count <= parameters.endNth)
                        && Global.getInt(cr, Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0) == 0
                        && Secure.getInt(cr,
                        Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, 0) == 0) {
                    showAutoBatterySaverSuggestion(context);
                }
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
        context.sendBroadcast(getSystemUiBroadcast(ACTION_SHOW_START_SAVER_CONFIRMATION));
        return true;
    }

    private static void showAutoBatterySaverSuggestion(Context context) {
        context.sendBroadcast(getSystemUiBroadcast(ACTION_SHOW_AUTO_SAVER_SUGGESTION));
    }

    private static Intent getSystemUiBroadcast(String action) {
        final Intent i = new Intent(action);
        i.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        i.setPackage(SYSUI_PACKAGE);
        return i;
    }

    private static void setBatterySaverConfirmationAcknowledged(Context context) {
        Secure.putInt(context.getContentResolver(), Secure.LOW_POWER_WARNING_ACKNOWLEDGED, 1);
    }

    /**
     * Don't show the automatic battery suggestion notification in the future.
     */
    public static void suppressAutoBatterySaver(Context context) {
        Secure.putInt(context.getContentResolver(),
                Secure.SUPPRESS_AUTO_BATTERY_SAVER_SUGGESTION, 1);
    }

    /**
     * Set the automatic battery saver trigger level to {@code level}.
     */
    public static void setAutoBatterySaverTriggerLevel(Context context, int level) {
        if (level > 0) {
            suppressAutoBatterySaver(context);
        }
        Global.putInt(context.getContentResolver(), Global.LOW_POWER_MODE_TRIGGER_LEVEL, level);
    }

    /**
     * Set the automatic battery saver trigger level to {@code level}, but only when
     * automatic battery saver isn't enabled yet.
     */
    public static void ensureAutoBatterySaver(Context context, int level) {
        if (Global.getInt(context.getContentResolver(), Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0)
                == 0) {
            setAutoBatterySaverTriggerLevel(context, level);
        }
    }
}
