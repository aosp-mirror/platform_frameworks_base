/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.intelligence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.service.intelligence.IIntelligenceService;
import android.service.intelligence.InteractionContext;
import android.service.intelligence.InteractionSessionId;
import android.text.format.DateUtils;
import android.util.Slog;

import com.android.server.AbstractRemoteService;

final class RemoteIntelligenceService extends AbstractRemoteService {

    private static final String TAG = "RemoteIntelligenceService";

    private static final long TIMEOUT_IDLE_BIND_MILLIS = 2 * DateUtils.MINUTE_IN_MILLIS;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.MINUTE_IN_MILLIS;

    private final RemoteIntelligenceServiceCallbacks mCallbacks;
    private IIntelligenceService mService;

    RemoteIntelligenceService(Context context, String serviceInterface,
            ComponentName componentName, int userId,
            RemoteIntelligenceServiceCallbacks callbacks, boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, serviceInterface, componentName, userId, callbacks,
                bindInstantServiceAllowed, verbose);
        mCallbacks = callbacks;
    }

    @Override // from RemoteService
    protected IInterface getServiceInterface(@NonNull IBinder service) {
        mService = IIntelligenceService.Stub.asInterface(service);
        return mService;
    }

    // TODO(b/111276913): modify super class to allow permanent binding when value is 0 or negative
    @Override // from RemoteService
    protected long getTimeoutIdleBindMillis() {
        // TODO(b/111276913): read from Settings so it can be changed in the field
        return TIMEOUT_IDLE_BIND_MILLIS;
    }

    @Override // from RemoteService
    protected long getRemoteRequestMillis() {
        // TODO(b/111276913): read from Settings so it can be changed in the field
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    /**
     * Called by {@link ContentCaptureSession} to generate a call to the
     * {@link RemoteIntelligenceService} to indicate the session was created (when {@code context}
     * is not {@code null} or destroyed (when {@code context} is {@code null}).
     */
    public void onSessionLifecycleRequest(@Nullable InteractionContext context,
            @NonNull InteractionSessionId sessionId) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingSessionLifecycleRequest(this, context, sessionId));
    }

    private static final class PendingSessionLifecycleRequest
            extends PendingRequest<RemoteIntelligenceService> {

        private final InteractionContext mContext;
        private final InteractionSessionId mSessionId;

        protected PendingSessionLifecycleRequest(@NonNull RemoteIntelligenceService service,
                @Nullable InteractionContext context, @NonNull InteractionSessionId sessionId) {
            super(service);
            mContext = context;
            mSessionId = sessionId;
        }

        @Override // from PendingRequest
        public void run() {
            final RemoteIntelligenceService remoteService = getService();
            if (remoteService != null) {
                try {
                    remoteService.mService.onSessionLifecycle(mContext, mSessionId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "exception handling PendingSessionLifecycleRequest for "
                            + mSessionId + ": " + e);
                    remoteService.mCallbacks
                        .onSessionLifecycleRequestFailureOrTimeout(/* timedOut= */ false);
                }
            }
        }

        @Override // from PendingRequest
        protected void onTimeout(RemoteIntelligenceService remoteService) {
            Slog.w(TAG, "timed out handling PendingSessionLifecycleRequest for "
                    + mSessionId);
            remoteService.mCallbacks
                .onSessionLifecycleRequestFailureOrTimeout(/* timedOut= */ true);
        }
    }

    public interface RemoteIntelligenceServiceCallbacks extends VultureCallback {
        void onSessionLifecycleRequestFailureOrTimeout(boolean timedOut);
    }
}
