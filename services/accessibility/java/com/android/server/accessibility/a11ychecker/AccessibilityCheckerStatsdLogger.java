/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.accessibility.a11ychecker;

import android.text.TextUtils;
import android.util.Slog;

import java.util.Set;


/**
 * Wraps the StatsdLogger for AccessibilityCheckResultReported.
 *
 * @hide
 */
public class AccessibilityCheckerStatsdLogger {
    private static final int ATOM_ID = 910;
    private static final String LOG_TAG = "AccessibilityCheckerStatsdLogger";

    /**
     * Writes results to statsd.
     */
    public static void logResults(Set<AndroidAccessibilityCheckerResult> results) {
        Slog.i(LOG_TAG, TextUtils.formatSimple("Writing %d AccessibilityCheckResultReported events",
                results.size()));

        for (AndroidAccessibilityCheckerResult result : results) {
            AccessibilityCheckerStatsLog.write(ATOM_ID,
                    result.getPackageName(),
                    result.getAppVersionCode(),
                    result.getUiElementPath(),
                    result.getActivityName(),
                    result.getWindowTitle(),
                    result.getSourceComponentName(),
                    result.getSourceVersionCode(),
                    result.getResultCheckClass().getNumber(),
                    result.getResultType().getNumber(),
                    result.getResultId());
        }
    }
}
