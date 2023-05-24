/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input;

import android.view.InputDevice;

import com.android.internal.util.FrameworkStatsLog;

/**
 * Collect Keyboard metrics
 */
public final class KeyboardMetricsCollector {
    private static final String TAG = "KeyboardMetricCollector";

    /**
     * Log keyboard system shortcuts for the proto
     * {@link com.android.os.input.KeyboardSystemsEventReported}
     * defined in "stats/atoms/input/input_extension_atoms.proto"
     */
    public static void logKeyboardSystemsEventReportedAtom(InputDevice inputDevice,
            int keyboardSystemEvent, int[] keyCode, int modifierState) {
        int vendor_id = inputDevice.getVendorId();
        int product_id = inputDevice.getProductId();
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED,
                vendor_id, product_id, keyboardSystemEvent, keyCode, modifierState);
    }
}
