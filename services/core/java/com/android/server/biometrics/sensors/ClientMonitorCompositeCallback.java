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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/** Holder for wrapping multiple handlers into a single Callback. */
public class ClientMonitorCompositeCallback implements ClientMonitorCallback {
    @NonNull
    private final List<ClientMonitorCallback> mCallbacks;

    public ClientMonitorCompositeCallback(@NonNull ClientMonitorCallback... callbacks) {
        mCallbacks = new ArrayList<>();

        for (ClientMonitorCallback callback : callbacks) {
            if (callback != null) {
                mCallbacks.add(callback);
            }
        }
    }

    @Override
    public final void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onClientStarted(clientMonitor);
        }
    }

    @Override
    public final void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
            boolean success) {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            mCallbacks.get(i).onClientFinished(clientMonitor, success);
        }
    }
}
