/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.util;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;

/**
 * A trigger implementation with perfetto backend.
 * @hide
 */
public class PerfettoTrigger {
    private static final String TAG = "PerfettoTrigger";
    private static final String TRIGGER_COMMAND = "/system/bin/trigger_perfetto";
    private static final long THROTTLE_MILLIS = 60000;
    private static volatile long sLastTriggerTime = -THROTTLE_MILLIS;

    /**
     * @param triggerName The name of the trigger. Must match the value defined in the AOT
     *                    Perfetto config.
     */
    public static void trigger(String triggerName) {
        // Trace triggering has a non-negligible cost (fork+exec).
        // To mitigate potential excessive triggering by the API client we ignore calls that happen
        // too quickl after the most recent trigger.
        long sinceLastTrigger = SystemClock.elapsedRealtime() - sLastTriggerTime;
        if (sinceLastTrigger < THROTTLE_MILLIS) {
            Log.v(TAG, "Not triggering " + triggerName + " - not enough time since last trigger");
            return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(TRIGGER_COMMAND, triggerName);
            Log.v(TAG, "Triggering " + String.join(" ", pb.command()));
            pb.start();
            sLastTriggerTime = SystemClock.elapsedRealtime();
        } catch (IOException e) {
            Log.w(TAG, "Failed to trigger " + triggerName, e);
        }
    }
}
