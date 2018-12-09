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
import android.os.IInterface;
import android.os.RemoteException;
import android.service.contentcapture.ContentCaptureEventsRequest;
import android.service.contentcapture.IContentCaptureService;
import android.service.contentcapture.InteractionContext;
import android.service.contentcapture.SnapshotData;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureEvent;

import com.android.server.infra.AbstractMultiplePendingRequestsRemoteService;

import java.util.List;

final class RemoteContentCaptureService
        extends AbstractMultiplePendingRequestsRemoteService<RemoteContentCaptureService> {

    private static final String TAG = RemoteContentCaptureService.class.getSimpleName();

    // TODO(b/117779333): changed it so it's permanentely bound
    private static final long TIMEOUT_IDLE_BIND_MILLIS = 2 * DateUtils.MINUTE_IN_MILLIS;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

    private final ContentCaptureServiceCallbacks mCallbacks;
    private IContentCaptureService mService;

    RemoteContentCaptureService(Context context, String serviceInterface,
            ComponentName componentName, int userId,
            ContentCaptureServiceCallbacks callbacks, boolean bindInstantServiceAllowed,
            boolean verbose) {
        super(context, serviceInterface, componentName, userId, callbacks,
                bindInstantServiceAllowed, verbose, /* initialCapacity= */ 2);
        mCallbacks = callbacks;
    }

    @Override // from RemoteService
    protected IInterface getServiceInterface(@NonNull IBinder service) {
        mService = IContentCaptureService.Stub.asInterface(service);
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
     * {@link RemoteContentCaptureService} to indicate the session was created (when {@code context}
     * is not {@code null} or destroyed (when {@code context} is {@code null}).
     */
    public void onSessionLifecycleRequest(@Nullable InteractionContext context,
            @NonNull String sessionId) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingSessionLifecycleRequest(this, context, sessionId));
    }

    /**
     * Called by {@link ContentCaptureSession} to send a batch of events to the service.
     */
    public void onContentCaptureEventsRequest(@NonNull String sessionId,
            @NonNull List<ContentCaptureEvent> events) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingOnContentCaptureEventsRequest(this, sessionId, events));
    }

    /**
     * Called by {@link ContentCaptureSession} to send snapshot data to the service.
     */
    public void onActivitySnapshotRequest(@NonNull String sessionId,
            @NonNull SnapshotData snapshotData) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingOnActivitySnapshotRequest(this, sessionId, snapshotData));
    }

    private abstract static class MyPendingRequest
            extends PendingRequest<RemoteContentCaptureService> {
        protected final String mSessionId;

        private MyPendingRequest(@NonNull RemoteContentCaptureService service,
                @NonNull String sessionId) {
            super(service);
            mSessionId = sessionId;
        }

        @Override // from PendingRequest
        protected final void onTimeout(RemoteContentCaptureService remoteService) {
            Slog.w(TAG, "timed out handling " + getClass().getSimpleName() + " for "
                    + mSessionId);
            remoteService.mCallbacks.onFailureOrTimeout(/* timedOut= */ true);
        }

        @Override // from PendingRequest
        public final void run() {
            final RemoteContentCaptureService remoteService = getService();
            if (remoteService != null) {
                try {
                    // We don't expect the service to call us back, so we finish right away.
                    myRun(remoteService);
                    // TODO(b/111330312): not true anymore!!
                    finish();
                } catch (RemoteException e) {
                    Slog.w(TAG, "exception handling " + getClass().getSimpleName() + " for "
                            + mSessionId + ": " + e);
                    remoteService.mCallbacks.onFailureOrTimeout(/* timedOut= */ false);
                }
            }
        }

        protected abstract void myRun(@NonNull RemoteContentCaptureService service)
                throws RemoteException;

    }

    private static final class PendingSessionLifecycleRequest extends MyPendingRequest {

        private final InteractionContext mContext;

        protected PendingSessionLifecycleRequest(@NonNull RemoteContentCaptureService service,
                @Nullable InteractionContext context, @NonNull String sessionId) {
            super(service, sessionId);
            mContext = context;
        }

        @Override // from MyPendingRequest
        public void myRun(@NonNull RemoteContentCaptureService remoteService)
                throws RemoteException {
            remoteService.mService.onSessionLifecycle(mContext, mSessionId);
        }
    }

    private static final class PendingOnContentCaptureEventsRequest extends MyPendingRequest {

        private final List<ContentCaptureEvent> mEvents;

        protected PendingOnContentCaptureEventsRequest(@NonNull RemoteContentCaptureService service,
                @NonNull String sessionId, @NonNull List<ContentCaptureEvent> events) {
            super(service, sessionId);
            mEvents = events;
        }

        @Override // from MyPendingRequest
        public void myRun(@NonNull RemoteContentCaptureService remoteService)
                throws RemoteException {
            remoteService.mService.onContentCaptureEventsRequest(mSessionId,
                    new ContentCaptureEventsRequest(mEvents));
        }
    }

    private static final class PendingOnActivitySnapshotRequest extends MyPendingRequest {

        private final SnapshotData mSnapshotData;

        protected PendingOnActivitySnapshotRequest(@NonNull RemoteContentCaptureService service,
                @NonNull String sessionId, @NonNull SnapshotData snapshotData) {
            super(service, sessionId);
            mSnapshotData = snapshotData;
        }

        @Override // from MyPendingRequest
        protected void myRun(@NonNull RemoteContentCaptureService remoteService)
                throws RemoteException {
            remoteService.mService.onActivitySnapshot(mSessionId, mSnapshotData);
        }
    }

    public interface ContentCaptureServiceCallbacks extends VultureCallback {
        // To keep it simple, we use the same callback for all failures / timeouts.
        void onFailureOrTimeout(boolean timedOut);
    }
}
