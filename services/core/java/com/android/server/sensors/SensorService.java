/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.sensors;

import android.content.Context;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.utils.TimingsTraceAndSlog;

import java.util.concurrent.Future;

public class SensorService extends SystemService {
    private static final String START_NATIVE_SENSOR_SERVICE = "StartNativeSensorService";
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private Future<?> mSensorServiceStart;


    /** Start the sensor service. This is a blocking call and can take time. */
    private static native void startNativeSensorService();

    public SensorService(Context ctx) {
        super(ctx);
        synchronized (mLock) {
            mSensorServiceStart = SystemServerInitThreadPool.submit(() -> {
                TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                traceLog.traceBegin(START_NATIVE_SENSOR_SERVICE);
                startNativeSensorService();
                traceLog.traceEnd();
            }, START_NATIVE_SENSOR_SERVICE);
        }
    }

    @Override
    public void onStart() { }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE) {
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart,
                    START_NATIVE_SENSOR_SERVICE);
            synchronized (mLock) {
                mSensorServiceStart = null;
            }
        }
    }
}
