/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.contentprotection;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.service.contentcapture.ContentCaptureService;
import android.service.contentcapture.IContentProtectionAllowlistCallback;
import android.service.contentcapture.IContentProtectionService;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureEvent;

import com.android.internal.infra.ServiceConnector;

/**
 * Connector for the remote content protection service.
 *
 * @hide
 */
public class RemoteContentProtectionService
        extends ServiceConnector.Impl<IContentProtectionService> {

    private static final String TAG = RemoteContentProtectionService.class.getSimpleName();

    @NonNull private final ComponentName mComponentName;

    private final long mAutoDisconnectTimeoutMs;

    public RemoteContentProtectionService(
            @NonNull Context context,
            @NonNull ComponentName componentName,
            int userId,
            boolean bindAllowInstant,
            long autoDisconnectTimeoutMs) {
        super(
                context,
                new Intent(ContentCaptureService.PROTECTION_SERVICE_INTERFACE)
                        .setComponent(componentName),
                bindAllowInstant ? Context.BIND_ALLOW_INSTANT : 0,
                userId,
                IContentProtectionService.Stub::asInterface);
        mComponentName = componentName;
        mAutoDisconnectTimeoutMs = autoDisconnectTimeoutMs;
    }

    @Override // from ServiceConnector.Impl
    protected long getAutoDisconnectTimeoutMs() {
        return mAutoDisconnectTimeoutMs;
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(
            @NonNull IContentProtectionService service, boolean isConnected) {
        Slog.i(
                TAG,
                "Connection status for: "
                        + mComponentName
                        + " changed to: "
                        + (isConnected ? "connected" : "disconnected"));
    }

    /** Calls the remote service when login is detected. */
    public void onLoginDetected(@NonNull ParceledListSlice<ContentCaptureEvent> events) {
        run(service -> service.onLoginDetected(events));
    }

    /** Calls the remote service with a request to update allowlist. */
    public void onUpdateAllowlistRequest(@NonNull IContentProtectionAllowlistCallback callback) {
        run(service -> service.onUpdateAllowlistRequest(callback.asBinder()));
    }
}
