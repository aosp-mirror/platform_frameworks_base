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

import static android.view.contentcapture.ContentCaptureSession.FLUSH_REASON_LOGIN_DETECTED;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.service.contentcapture.ContentCaptureService;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.IContentCaptureDirectManager;

import com.android.internal.infra.ServiceConnector;

import java.time.Duration;

/**
 * Connector for the remote content protection service.
 *
 * @hide
 */
class RemoteContentProtectionService extends ServiceConnector.Impl<IContentCaptureDirectManager> {

    private static final String TAG = RemoteContentProtectionService.class.getSimpleName();

    private static final Duration AUTO_DISCONNECT_TIMEOUT = Duration.ofSeconds(3);

    @NonNull private final ComponentName mComponentName;

    protected RemoteContentProtectionService(
            @NonNull Context context,
            @NonNull ComponentName componentName,
            int userId,
            boolean bindAllowInstant) {
        super(
                context,
                new Intent(ContentCaptureService.PROTECTION_SERVICE_INTERFACE)
                        .setComponent(componentName),
                bindAllowInstant ? Context.BIND_ALLOW_INSTANT : 0,
                userId,
                IContentCaptureDirectManager.Stub::asInterface);
        mComponentName = componentName;
    }

    @Override // from ServiceConnector.Impl
    protected long getAutoDisconnectTimeoutMs() {
        return AUTO_DISCONNECT_TIMEOUT.toMillis();
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(
            @NonNull IContentCaptureDirectManager service, boolean isConnected) {
        Slog.i(
                TAG,
                "Connection status for: "
                        + mComponentName
                        + " changed to: "
                        + (isConnected ? "connected" : "disconnected"));
    }

    public void onLoginDetected(@NonNull ParceledListSlice<ContentCaptureEvent> events) {
        run(
                service ->
                        service.sendEvents(
                                events, FLUSH_REASON_LOGIN_DETECTED, /* options= */ null));
    }
}
