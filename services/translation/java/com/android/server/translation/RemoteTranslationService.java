/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.translation;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ResultReceiver;
import android.service.translation.ITranslationService;
import android.service.translation.TranslationService;
import android.util.Slog;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationSpec;

import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.infra.ServiceConnector;
import com.android.internal.os.IResultReceiver;

final class RemoteTranslationService extends ServiceConnector.Impl<ITranslationService> {

    private static final String TAG = RemoteTranslationService.class.getSimpleName();

    // TODO(b/176590870): Make PERMANENT now.
    private static final long TIMEOUT_IDLE_UNBIND_MS =
            AbstractRemoteService.PERMANENT_BOUND_TIMEOUT_MS;
    private static final int TIMEOUT_REQUEST_MS = 5_000;

    private final long mIdleUnbindTimeoutMs;
    private final int mRequestTimeoutMs;
    private final ComponentName mComponentName;

    RemoteTranslationService(Context context, ComponentName serviceName,
            int userId, boolean bindInstantServiceAllowed) {
        super(context,
                new Intent(TranslationService.SERVICE_INTERFACE).setComponent(serviceName),
                bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0,
                userId, ITranslationService.Stub::asInterface);
        mIdleUnbindTimeoutMs = TIMEOUT_IDLE_UNBIND_MS;
        mRequestTimeoutMs = TIMEOUT_REQUEST_MS;
        mComponentName = serviceName;

        // Bind right away.
        connect();
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    @Override // from ServiceConnector.Impl
    protected void onServiceConnectionStatusChanged(ITranslationService service,
            boolean connected) {
        try {
            if (connected) {
                service.onConnected();
            } else {
                service.onDisconnected();
            }
        } catch (Exception e) {
            Slog.w(TAG,
                    "Exception calling onServiceConnectionStatusChanged(" + connected + "): ", e);
        }
    }

    @Override // from AbstractRemoteService
    protected long getAutoDisconnectTimeoutMs() {
        return mIdleUnbindTimeoutMs;
    }

    public void onSessionCreated(@NonNull TranslationContext translationContext, int sessionId,
            IResultReceiver resultReceiver) {
        run((s) -> s.onCreateTranslationSession(translationContext, sessionId, resultReceiver));
    }

    public void onTranslationCapabilitiesRequest(@TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull ResultReceiver resultReceiver) {
        run((s) -> s.onTranslationCapabilitiesRequest(sourceFormat, targetFormat, resultReceiver));
    }
}
