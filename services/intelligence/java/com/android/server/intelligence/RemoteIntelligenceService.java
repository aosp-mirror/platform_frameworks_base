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
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.service.intelligence.ContentCaptureEventsRequest;
import android.service.intelligence.IIntelligenceService;
import android.service.intelligence.InteractionContext;
import android.service.intelligence.InteractionSessionId;
import android.service.intelligence.SnapshotData;
import android.text.format.DateUtils;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.IAutoFillManagerClient;
import android.view.intelligence.ContentCaptureEvent;

import com.android.internal.os.IResultReceiver;
import com.android.server.AbstractRemoteService;

import java.util.List;

//TODO(b/111276913): rename once the final name is defined
final class RemoteIntelligenceService extends AbstractRemoteService {

    private static final String TAG = "RemoteIntelligenceService";

    private static final long TIMEOUT_IDLE_BIND_MILLIS = 2 * DateUtils.MINUTE_IN_MILLIS;
    private static final long TIMEOUT_REMOTE_REQUEST_MILLIS = 2 * DateUtils.SECOND_IN_MILLIS;

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

    /**
     * Called by {@link ContentCaptureSession} to send a batch of events to the service.
     */
    public void onContentCaptureEventsRequest(@NonNull InteractionSessionId sessionId,
            @NonNull List<ContentCaptureEvent> events) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingOnContentCaptureEventsRequest(this, sessionId, events));
    }

    /**
     * Called by {@link ContentCaptureSession} to send snapshot data to the service.
     */
    public void onActivitySnapshotRequest(@NonNull InteractionSessionId sessionId,
            @NonNull SnapshotData snapshotData) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingOnActivitySnapshotRequest(this, sessionId, snapshotData));
    }

    /**
     * Called by {@link ContentCaptureSession} to request augmented autofill.
     */
    public void onRequestAutofillLocked(@NonNull InteractionSessionId sessionId,
            @NonNull IAutoFillManagerClient client, int autofillSessionId,
            @NonNull AutofillId focusedId) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingAutofillRequest(this, sessionId, client, autofillSessionId,
                focusedId));
    }

    /**
     * Called by {@link ContentCaptureSession} when it's time to destroy all augmented autofill
     * requests.
     */
    public void onDestroyAutofillWindowsRequest(@NonNull InteractionSessionId sessionId) {
        cancelScheduledUnbind();
        scheduleRequest(new PendingDestroyAutofillWindowsRequest(this, sessionId));
    }

    private abstract static class MyPendingRequest
            extends PendingRequest<RemoteIntelligenceService> {
        protected final InteractionSessionId mSessionId;

        private MyPendingRequest(@NonNull RemoteIntelligenceService service,
                @NonNull InteractionSessionId sessionId) {
            super(service);
            mSessionId = sessionId;
        }

        @Override // from PendingRequest
        protected final void onTimeout(RemoteIntelligenceService remoteService) {
            Slog.w(TAG, "timed out handling " + getClass().getSimpleName() + " for "
                    + mSessionId);
            remoteService.mCallbacks.onFailureOrTimeout(/* timedOut= */ true);
        }

        @Override // from PendingRequest
        public final void run() {
            final RemoteIntelligenceService remoteService = getService();
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

        protected abstract void myRun(@NonNull RemoteIntelligenceService service)
                throws RemoteException;

    }

    private static final class PendingSessionLifecycleRequest extends MyPendingRequest {

        private final InteractionContext mContext;

        protected PendingSessionLifecycleRequest(@NonNull RemoteIntelligenceService service,
                @Nullable InteractionContext context, @NonNull InteractionSessionId sessionId) {
            super(service, sessionId);
            mContext = context;
        }

        @Override // from MyPendingRequest
        public void myRun(@NonNull RemoteIntelligenceService remoteService) throws RemoteException {
            remoteService.mService.onSessionLifecycle(mContext, mSessionId);
        }
    }

    private static final class PendingOnContentCaptureEventsRequest extends MyPendingRequest {

        private final List<ContentCaptureEvent> mEvents;

        protected PendingOnContentCaptureEventsRequest(@NonNull RemoteIntelligenceService service,
                @NonNull InteractionSessionId sessionId,
                @NonNull List<ContentCaptureEvent> events) {
            super(service, sessionId);
            mEvents = events;
        }

        @Override // from MyPendingRequest
        public void myRun(@NonNull RemoteIntelligenceService remoteService) throws RemoteException {
            remoteService.mService.onContentCaptureEventsRequest(mSessionId,
                    new ContentCaptureEventsRequest(mEvents));
        }
    }

    private static final class PendingOnActivitySnapshotRequest extends MyPendingRequest {

        private final SnapshotData mSnapshotData;

        protected PendingOnActivitySnapshotRequest(@NonNull RemoteIntelligenceService service,
                @NonNull InteractionSessionId sessionId,
                @NonNull SnapshotData snapshotData) {
            super(service, sessionId);
            mSnapshotData = snapshotData;
        }

        @Override // from MyPendingRequest
        protected void myRun(@NonNull RemoteIntelligenceService remoteService)
                throws RemoteException {
            remoteService.mService.onActivitySnapshot(mSessionId, mSnapshotData);
        }
    }

    private static final class PendingAutofillRequest extends MyPendingRequest {
        private final @NonNull AutofillId mFocusedId;
        private final @NonNull IAutoFillManagerClient mClient;
        private final int mAutofillSessionId;

        protected PendingAutofillRequest(@NonNull RemoteIntelligenceService service,
                @NonNull InteractionSessionId sessionId, @NonNull IAutoFillManagerClient client,
                int autofillSessionId, @NonNull AutofillId focusedId) {
            super(service, sessionId);
            mClient = client;
            mAutofillSessionId = autofillSessionId;
            mFocusedId = focusedId;
        }

        @Override // from MyPendingRequest
        public void myRun(@NonNull RemoteIntelligenceService remoteService) throws RemoteException {
            final IResultReceiver receiver = new IResultReceiver.Stub() {

                @Override
                public void send(int resultCode, Bundle resultData) throws RemoteException {
                    final IBinder realClient = resultData
                            .getBinder(AutofillManager.EXTRA_AUGMENTED_AUTOFILL_CLIENT);
                    remoteService.mService.onAutofillRequest(mSessionId, realClient,
                            mAutofillSessionId, mFocusedId);
                }
            };

            // TODO(b/111330312): set cancellation signal, timeout (from  both mClient and service),
            // cache IAugmentedAutofillManagerClient reference, etc...
            mClient.getAugmentedAutofillClient(receiver);
        }
    }

    private static final class PendingDestroyAutofillWindowsRequest extends MyPendingRequest {

        protected PendingDestroyAutofillWindowsRequest(@NonNull RemoteIntelligenceService service,
                @NonNull InteractionSessionId sessionId) {
            super(service, sessionId);
        }

        @Override
        protected void myRun(@NonNull RemoteIntelligenceService service) throws RemoteException {
            service.mService.onDestroyAutofillWindowsRequest(mSessionId);
            // TODO(b/111330312): implement timeout
        }
    }

    public interface RemoteIntelligenceServiceCallbacks extends VultureCallback {
        // To keep it simple, we use the same callback for all failures / timeouts.
        void onFailureOrTimeout(boolean timedOut);
    }
}
