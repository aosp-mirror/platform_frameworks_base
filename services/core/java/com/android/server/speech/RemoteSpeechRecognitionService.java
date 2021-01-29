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

package com.android.server.speech;

import static com.android.internal.infra.AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.speech.IRecognitionListener;
import android.speech.IRecognitionService;
import android.speech.RecognitionService;
import android.util.Slog;

import com.android.internal.infra.ServiceConnector;

final class RemoteSpeechRecognitionService extends ServiceConnector.Impl<IRecognitionService> {
    private static final String TAG = RemoteSpeechRecognitionService.class.getSimpleName();
    private static final boolean DEBUG = true;

    RemoteSpeechRecognitionService(Context context, ComponentName serviceName, int userId) {
        super(context,
                new Intent(RecognitionService.SERVICE_INTERFACE).setComponent(serviceName),
                Context.BIND_AUTO_CREATE
                        | Context.BIND_FOREGROUND_SERVICE
                        | Context.BIND_INCLUDE_CAPABILITIES
                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                userId,
                IRecognitionService.Stub::asInterface);

        if (DEBUG) {
            Slog.i(TAG, "Bound to recognition service at: " + serviceName.flattenToString());
        }
    }

    void startListening(Intent recognizerIntent, IRecognitionListener listener, String packageName,
            String featureId) throws RemoteException {
        if (DEBUG) {
            Slog.i(TAG, "#startListening for package: " + packageName + ", feature=" + featureId);
        }
        run(service -> service.startListening(recognizerIntent, listener, packageName, featureId));
    }

    void stopListening(IRecognitionListener listener, String packageName, String featureId)
            throws RemoteException {
        if (DEBUG) {
            Slog.i(TAG, "#stopListening for package: " + packageName + ", feature=" + featureId);
        }
        run(service -> service.stopListening(listener, packageName, featureId));
    }

    void cancel(IRecognitionListener listener, String packageName, String featureId)
            throws RemoteException {
        if (DEBUG) {
            Slog.i(TAG, "#cancel for package: " + packageName + ", feature=" + featureId);
        }
        run(service -> service.cancel(listener, packageName, featureId));
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(
            IRecognitionService service, boolean connected) {
        if (!DEBUG) {
            return;
        }

        if (connected) {
            Slog.i(TAG, "Connected to ASR service");
        } else {
            Slog.w(TAG, "Disconnected from ASR service");
        }
    }

    @Override // from AbstractRemoteService
    protected long getAutoDisconnectTimeoutMs() {
        return PERMANENT_BOUND_TIMEOUT_MS;
    }
}
