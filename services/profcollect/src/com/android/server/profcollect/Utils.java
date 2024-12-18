/**
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.profcollect;

import static com.android.server.profcollect.ProfcollectForwardingService.LOG_TAG;

import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.os.BackgroundThread;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

final class Utils {

    private static Instant lastTraceTime = Instant.EPOCH;
    private static final int TRACE_COOLDOWN_SECONDS = 30;

    static boolean withFrequency(String configName, int defaultFrequency) {
        int threshold = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_PROFCOLLECT_NATIVE_BOOT, configName, defaultFrequency);
        int randomNum = ThreadLocalRandom.current().nextInt(100);
        return randomNum < threshold;
    }

    /**
     * Request a system-wide trace.
     * Will be ignored if the device does not meet trace criteria or is being rate limited.
     */
    static boolean traceSystem(IProfCollectd iprofcollectd, String eventName) {
        if (!checkPrerequisites(iprofcollectd)) {
            return false;
        }
        BackgroundThread.get().getThreadHandler().post(() -> {
            try {
                iprofcollectd.trace_system(eventName);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * Request a system-wide trace after a delay.
     * Will be ignored if the device does not meet trace criteria or is being rate limited.
     */
    static boolean traceSystem(IProfCollectd iprofcollectd, String eventName, int delayMs) {
        if (!checkPrerequisites(iprofcollectd)) {
            return false;
        }
        BackgroundThread.get().getThreadHandler().postDelayed(() -> {
            try {
                iprofcollectd.trace_system(eventName);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
            }
        }, delayMs);
        return true;
    }

    /**
     * Request a single-process trace.
     * Will be ignored if the device does not meet trace criteria or is being rate limited.
     */
    static boolean traceProcess(IProfCollectd iprofcollectd,
            String eventName, String processName, int durationMs) {
        if (!checkPrerequisites(iprofcollectd)) {
            return false;
        }
        BackgroundThread.get().getThreadHandler().post(() -> {
            try {
                iprofcollectd.trace_process(eventName,
                        processName,
                        durationMs);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * Returns true if the last trace is within the cooldown period. If the last trace is outside
     * the cooldown period, the last trace time is reset to the current time.
     */
    private static boolean isInCooldownOrReset() {
        if (!Instant.now().isBefore(lastTraceTime.plusSeconds(TRACE_COOLDOWN_SECONDS))) {
            lastTraceTime = Instant.now();
            return false;
        }
        return true;
    }

    private static boolean checkPrerequisites(IProfCollectd iprofcollectd) {
        if (iprofcollectd == null) {
            return false;
        }
        if (isInCooldownOrReset()) {
            return false;
        }
        return ProfcollectForwardingService.sVerityEnforced
            && !ProfcollectForwardingService.sAdbActive
            && ProfcollectForwardingService.sIsInteractive
            && !ProfcollectForwardingService.sIsBatteryLow;
    }
}
