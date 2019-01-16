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
package com.android.server.contentcapture;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.service.contentcapture.IContentCaptureService;
import android.service.contentcapture.IContentCaptureServiceCallback;
import android.service.contentcapture.SnapshotData;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureContext;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.internal.os.IResultReceiver;

final class RemoteContentCaptureService
        extends AbstractMultiplePendingRequestsRemoteService<RemoteContentCaptureService,
        IContentCaptureService> {

    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    private final IBinder mServerCallback;

    RemoteContentCaptureService(Context context, String serviceInterface,
            ComponentName serviceComponentName, IContentCaptureServiceCallback callback, int userId,
            ContentCaptureServiceCallbacks callbacks, boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, serviceInterface, serviceComponentName, userId, callbacks,
                bindInstantServiceAllowed, verbose, /* initialCapacity= */ 2);
        mServerCallback = callback.asBinder();

        // Bind right away, which will trigger a onConnected() on service's
        scheduleBind();
    }

    @Override // from AbstractRemoteService
    protected IContentCaptureService getServiceInterface(@NonNull IBinder service) {
        return IContentCaptureService.Stub.asInterface(service);
    }

    @Override // from AbstractRemoteService
    protected long getTimeoutIdleBindMillis() {
        // TODO(b/111276913): read from Settings so it can be changed in the field
        return PERMANENT_BOUND_TIMEOUT_MS;
    }

    @Override // from AbstractRemoteService
    protected long getRemoteRequestMillis() {
        // TODO(b/111276913): read from Settings so it can be changed in the field
        return TIMEOUT_REMOTE_REQUEST_MILLIS;
    }

    @Override // from RemoteService
    protected void handleOnConnectedStateChanged(boolean state) {
        if (state && getTimeoutIdleBindMillis() != PERMANENT_BOUND_TIMEOUT_MS) {
            scheduleUnbind();
        }
        try {
            if (state) {
                mService.onConnected(mServerCallback);
            } else {
                mService.onDisconnected();
            }
        } catch (Exception e) {
            Slog.w(mTag, "Exception calling onConnectedStateChanged(" + state + "): " + e);
        }
    }

    /**
     * Called by {@link ContentCaptureServerSession} to generate a call to the
     * {@link RemoteContentCaptureService} to indicate the session was created.
     */
    public void onSessionStarted(@Nullable ContentCaptureContext context,
            @NonNull String sessionId, int uid, @NonNull IResultReceiver clientReceiver) {
        scheduleAsyncRequest((s) -> s.onSessionStarted(context, sessionId, uid, clientReceiver));
    }

    /**
     * Called by {@link ContentCaptureServerSession} to generate a call to the
     * {@link RemoteContentCaptureService} to indicate the session was finished.
     */
    public void onSessionFinished(@NonNull String sessionId) {
        scheduleAsyncRequest((s) -> s.onSessionFinished(sessionId));
    }

    /**
     * Called by {@link ContentCaptureServerSession} to send snapshot data to the service.
     */
    public void onActivitySnapshotRequest(@NonNull String sessionId,
            @NonNull SnapshotData snapshotData) {
        scheduleAsyncRequest((s) -> s.onActivitySnapshot(sessionId, snapshotData));
    }

    public interface ContentCaptureServiceCallbacks
            extends VultureCallback<RemoteContentCaptureService> {
        // NOTE: so far we don't need to notify the callback implementation
        // (ContentCaptureServerSession) of the request results (success, timeouts, etc..), so this
        // callback interface is empty.
    }
}
