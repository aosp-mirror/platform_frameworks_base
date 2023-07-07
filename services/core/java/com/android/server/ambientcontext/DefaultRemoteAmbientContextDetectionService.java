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

package com.android.server.ambientcontext;

import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;

import android.annotation.NonNull;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteCallback;
import android.service.ambientcontext.AmbientContextDetectionService;
import android.service.ambientcontext.IAmbientContextDetectionService;
import android.util.Slog;

import com.android.internal.infra.ServiceConnector;

import java.io.PrintWriter;

/** Manages the connection to the remote service. */
final class DefaultRemoteAmbientContextDetectionService
        extends ServiceConnector.Impl<IAmbientContextDetectionService>
        implements RemoteAmbientDetectionService {
    private static final String TAG =
            DefaultRemoteAmbientContextDetectionService.class.getSimpleName();

    DefaultRemoteAmbientContextDetectionService(Context context, ComponentName serviceName,
            int userId) {
        super(context, new Intent(
                AmbientContextDetectionService.SERVICE_INTERFACE).setComponent(serviceName),
                BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES, userId,
                IAmbientContextDetectionService.Stub::asInterface);

        // Bind right away
        connect();
    }

    @Override
    protected long getAutoDisconnectTimeoutMs() {
        // Disable automatic unbinding.
        return -1;
    }

    @Override
    public void startDetection(
            @NonNull AmbientContextEventRequest request, String packageName,
            RemoteCallback detectionResultCallback, RemoteCallback statusCallback) {
        Slog.i(TAG, "Start detection for " + request.getEventTypes());
        post(service -> service.startDetection(request, packageName, detectionResultCallback,
                statusCallback));
    }

    @Override
    public void stopDetection(String packageName) {
        Slog.i(TAG, "Stop detection for " + packageName);
        post(service -> service.stopDetection(packageName));
    }

    @Override
    public void queryServiceStatus(
            @AmbientContextEvent.EventCode int[] eventTypes,
            String packageName,
            RemoteCallback callback) {
        Slog.i(TAG, "Query status for " + packageName);
        post(service -> service.queryServiceStatus(eventTypes, packageName, callback));
    }

    @Override
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        super.dump(prefix, pw);
    }

    @Override
    public void unbind() {
        super.unbind();
    }
}
