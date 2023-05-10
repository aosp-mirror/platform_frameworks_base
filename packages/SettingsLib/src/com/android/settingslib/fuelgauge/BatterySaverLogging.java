/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Utilities related to battery saver logging.
 */
public final class BatterySaverLogging {
    /**
     * Record the reason while enabling power save mode manually.
     * See {@link SaverManualEnabledReason} for all available states.
     */
    public static final String EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED_REASON =
            "extra_power_save_mode_manual_enabled_reason";

    /** Record the event while enabling power save mode manually. */
    public static final String EXTRA_POWER_SAVE_MODE_MANUAL_ENABLED =
            "extra_power_save_mode_manual_enabled";

    /** Broadcast action to record battery saver manual enabled reason. */
    public static final String ACTION_SAVER_STATE_MANUAL_UPDATE =
            "com.android.settingslib.fuelgauge.ACTION_SAVER_STATE_MANUAL_UPDATE";

    /** An interface for the battery saver manual enable reason. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SAVER_ENABLED_UNKNOWN, SAVER_ENABLED_CONFIRMATION, SAVER_ENABLED_VOICE,
            SAVER_ENABLED_SETTINGS, SAVER_ENABLED_QS, SAVER_ENABLED_LOW_WARNING,
            SAVER_ENABLED_SEVERE_WARNING})
    public @interface SaverManualEnabledReason {}

    public static final int SAVER_ENABLED_UNKNOWN = 0;
    public static final int SAVER_ENABLED_CONFIRMATION = 1;
    public static final int SAVER_ENABLED_VOICE = 2;
    public static final int SAVER_ENABLED_SETTINGS = 3;
    public static final int SAVER_ENABLED_QS = 4;
    public static final int SAVER_ENABLED_LOW_WARNING = 5;
    public static final int SAVER_ENABLED_SEVERE_WARNING = 6;
}
