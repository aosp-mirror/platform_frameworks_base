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
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.os.BackgroundThread;

import java.util.concurrent.ThreadLocalRandom;

public final class Utils {

    public static boolean withFrequency(String configName, int defaultFrequency) {
        int threshold = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_PROFCOLLECT_NATIVE_BOOT, configName, defaultFrequency);
        int randomNum = ThreadLocalRandom.current().nextInt(100);
        return randomNum < threshold;
    }

    public static boolean traceSystem(IProfCollectd mIProfcollect, String eventName) {
        if (mIProfcollect == null) {
            return false;
        }
        BackgroundThread.get().getThreadHandler().post(() -> {
            try {
                mIProfcollect.trace_system(eventName);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
            }
        });
        return true;
    }

    public static boolean traceSystem(IProfCollectd mIProfcollect, String eventName, int delayMs) {
        if (mIProfcollect == null) {
            return false;
        }
        BackgroundThread.get().getThreadHandler().postDelayed(() -> {
            try {
                mIProfcollect.trace_system(eventName);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
            }
        }, delayMs);
        return true;
    }

    public static boolean traceProcess(IProfCollectd mIProfcollect,
            String eventName, String processName, int durationMs) {
        if (mIProfcollect == null) {
            return false;
        }
        BackgroundThread.get().getThreadHandler().post(() -> {
            try {
                mIProfcollect.trace_process(eventName,
                        processName,
                        durationMs);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
            }
        });
        return true;
    }
}