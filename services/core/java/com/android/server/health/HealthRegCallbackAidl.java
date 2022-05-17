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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.health.HealthInfo;
import android.hardware.health.IHealth;
import android.hardware.health.IHealthInfoCallback;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

/**
 * On service registration, {@link #onRegistration} is called, which registers {@code this}, an
 * {@link IHealthInfoCallback}, to the health service.
 *
 * <p>When the health service has updates to health info via {@link IHealthInfoCallback}, {@link
 * HealthInfoCallback#update} is called.
 *
 * <p>AIDL variant of {@link HealthHalCallbackHidl}.
 *
 * @hide
 */
// It is made public so Mockito can access this class. It should have been package private if not
// for testing.
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class HealthRegCallbackAidl {
    private static final String TAG = "HealthRegCallbackAidl";
    private final HealthInfoCallback mServiceInfoCallback;
    private final IHealthInfoCallback mHalInfoCallback = new HalInfoCallback();

    HealthRegCallbackAidl(@Nullable HealthInfoCallback healthInfoCallback) {
        mServiceInfoCallback = healthInfoCallback;
    }

    /**
     * Called when the service manager sees {@code newService} replacing {@code oldService}.
     * This unregisters the health info callback from the old service (ignoring errors), then
     * registers the health info callback to the new service.
     *
     * @param oldService the old IHealth service
     * @param newService the new IHealth service
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public void onRegistration(@Nullable IHealth oldService, @NonNull IHealth newService) {
        if (mServiceInfoCallback == null) return;

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "HealthUnregisterCallbackAidl");
        try {
            unregisterCallback(oldService, mHalInfoCallback);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }

        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "HealthRegisterCallbackAidl");
        try {
            registerCallback(newService, mHalInfoCallback);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    private static void unregisterCallback(@Nullable IHealth oldService, IHealthInfoCallback cb) {
        if (oldService == null) return;
        try {
            oldService.unregisterCallback(cb);
        } catch (RemoteException e) {
            // Ignore errors. The service might have died.
            Slog.w(
                    TAG,
                    "health: cannot unregister previous callback (transaction error): "
                            + e.getMessage());
        }
    }

    private static void registerCallback(@NonNull IHealth newService, IHealthInfoCallback cb) {
        try {
            newService.registerCallback(cb);
        } catch (RemoteException e) {
            Slog.e(
                    TAG,
                    "health: cannot register callback, framework may cease to"
                            + " receive updates on health / battery info!",
                    e);
            return;
        }
        // registerCallback does NOT guarantee that update is called immediately, so request a
        // manual update here.
        try {
            newService.update();
        } catch (RemoteException e) {
            Slog.e(TAG, "health: cannot update after registering health info callback", e);
        }
    }

    private class HalInfoCallback extends IHealthInfoCallback.Stub {
        @Override
        public void healthInfoChanged(HealthInfo healthInfo) throws RemoteException {
            mServiceInfoCallback.update(healthInfo);
        }
        @Override
        public String getInterfaceHash() {
            return IHealthInfoCallback.HASH;
        }
        @Override
        public int getInterfaceVersion() {
            return IHealthInfoCallback.VERSION;
        }
    }
}
