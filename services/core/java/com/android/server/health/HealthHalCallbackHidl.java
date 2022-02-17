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

package com.android.server.health;

import static android.hardware.health.Translate.h2aTranslate;

import android.annotation.NonNull;
import android.hardware.health.V2_0.IHealth;
import android.hardware.health.V2_0.Result;
import android.hardware.health.V2_1.BatteryCapacityLevel;
import android.hardware.health.V2_1.Constants;
import android.hardware.health.V2_1.IHealthInfoCallback;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;

/**
 * On service registration, {@link HealthServiceWrapperHidl.Callback#onRegistration} is called,
 * which registers {@code this}, a {@link IHealthInfoCallback}, to the health service.
 *
 * <p>When the health service has updates to health info, {@link HealthInfoCallback#update} is
 * called.
 *
 * @hide
 */
class HealthHalCallbackHidl extends IHealthInfoCallback.Stub
        implements HealthServiceWrapperHidl.Callback {

    private static final String TAG = HealthHalCallbackHidl.class.getSimpleName();

    private static void traceBegin(String name) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, name);
    }

    private static void traceEnd() {
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private HealthInfoCallback mCallback;

    HealthHalCallbackHidl(@NonNull HealthInfoCallback callback) {
        mCallback = callback;
    }

    @Override
    public void healthInfoChanged(android.hardware.health.V2_0.HealthInfo props) {
        android.hardware.health.V2_1.HealthInfo propsLatest =
                new android.hardware.health.V2_1.HealthInfo();
        propsLatest.legacy = props;

        propsLatest.batteryCapacityLevel = BatteryCapacityLevel.UNSUPPORTED;
        propsLatest.batteryChargeTimeToFullNowSeconds =
                Constants.BATTERY_CHARGE_TIME_TO_FULL_NOW_SECONDS_UNSUPPORTED;

        mCallback.update(h2aTranslate(propsLatest));
    }

    @Override
    public void healthInfoChanged_2_1(android.hardware.health.V2_1.HealthInfo props) {
        mCallback.update(h2aTranslate(props));
    }

    // on new service registered
    @Override
    public void onRegistration(IHealth oldService, IHealth newService, String instance) {
        if (newService == null) return;

        traceBegin("HealthUnregisterCallback");
        try {
            if (oldService != null) {
                int r = oldService.unregisterCallback(this);
                if (r != Result.SUCCESS) {
                    Slog.w(
                            TAG,
                            "health: cannot unregister previous callback: " + Result.toString(r));
                }
            }
        } catch (RemoteException ex) {
            Slog.w(
                    TAG,
                    "health: cannot unregister previous callback (transaction error): "
                            + ex.getMessage());
        } finally {
            traceEnd();
        }

        traceBegin("HealthRegisterCallback");
        try {
            int r = newService.registerCallback(this);
            if (r != Result.SUCCESS) {
                Slog.w(TAG, "health: cannot register callback: " + Result.toString(r));
                return;
            }
            // registerCallback does NOT guarantee that update is called
            // immediately, so request a manual update here.
            newService.update();
        } catch (RemoteException ex) {
            Slog.e(TAG, "health: cannot register callback (transaction error): " + ex.getMessage());
        } finally {
            traceEnd();
        }
    }
}
