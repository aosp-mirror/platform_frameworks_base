/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import android.annotation.NonNull;

import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.ClientMonitorCallback;

/**
 * Client monitor callback that exposes a probe.
 *
 * Disables the probe when the operation completes.
 *
 * @param <T> probe type
 */
public class CallbackWithProbe<T extends Probe> implements ClientMonitorCallback {
    private final boolean mStartWithClient;
    private final T mProbe;

    public CallbackWithProbe(@NonNull T probe, boolean startWithClient) {
        mProbe = probe;
        mStartWithClient = startWithClient;
    }

    @Override
    public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
        if (mStartWithClient) {
            mProbe.enable();
        }
    }

    @Override
    public void onClientFinished(@NonNull BaseClientMonitor clientMonitor, boolean success) {
        mProbe.destroy();
    }

    @NonNull
    public T getProbe() {
        return mProbe;
    }
}
