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

import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;

import static com.android.server.contentcapture.ContentCaptureMetricsLogger.writeServiceEvent;
import static com.android.server.contentcapture.ContentCaptureMetricsLogger.writeSessionEvent;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.service.contentcapture.ActivityEvent;
import android.service.contentcapture.IContentCaptureService;
import android.service.contentcapture.IContentCaptureServiceCallback;
import android.service.contentcapture.IDataShareCallback;
import android.service.contentcapture.SnapshotData;
import android.util.EventLog;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.DataRemovalRequest;
import android.view.contentcapture.DataShareRequest;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FrameworkStatsLog;

final class RemoteContentCaptureService
        extends AbstractMultiplePendingRequestsRemoteService<RemoteContentCaptureService,
        IContentCaptureService> {

    private final IBinder mServerCallback;
    private final int mIdleUnbindTimeoutMs;
    private final ContentCapturePerUserService mPerUserService;

    RemoteContentCaptureService(Context context, String serviceInterface,
            ComponentName serviceComponentName, IContentCaptureServiceCallback callback, int userId,
            ContentCapturePerUserService perUserService, boolean bindInstantServiceAllowed,
            boolean verbose, int idleUnbindTimeoutMs) {
        super(context, serviceInterface, serviceComponentName, userId, perUserService,
                context.getMainThreadHandler(),
                Context.BIND_INCLUDE_CAPABILITIES
                        | (bindInstantServiceAllowed ? Context.BIND_ALLOW_INSTANT : 0),
                verbose,
                /* initialCapacity= */ 2);
        mPerUserService = perUserService;
        mServerCallback = callback.asBinder();
        mIdleUnbindTimeoutMs = idleUnbindTimeoutMs;

        // Bind right away, which will trigger a onConnected() on service's
        ensureBoundLocked();
    }

    @Override // from AbstractRemoteService
    protected IContentCaptureService getServiceInterface(@NonNull IBinder service) {
        return IContentCaptureService.Stub.asInterface(service);
    }

    @Override // from AbstractRemoteService
    protected long getTimeoutIdleBindMillis() {
        return mIdleUnbindTimeoutMs;
    }

    @Override // from AbstractRemoteService
    protected void handleOnConnectedStateChanged(boolean connected) {
        if (connected && getTimeoutIdleBindMillis() != PERMANENT_BOUND_TIMEOUT_MS) {
            scheduleUnbind();
        }
        try {
            if (connected) {
                try {
                    mService.onConnected(mServerCallback, sVerbose, sDebug);
                    writeServiceEvent(
                            FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__ON_CONNECTED,
                            mComponentName);
                    EventLog.writeEvent(EventLogTags.CC_CONNECT_STATE_CHANGED,
                            mPerUserService.getUserId(),
                            ContentCapturePerUserService.EVENT_LOG_CONNECT_STATE_CONNECTED,
                            CollectionUtils.size(mPerUserService.getContentCaptureAllowlist()));
                } finally {
                    // Update the system-service state, in case the service reconnected after
                    // dying
                    mPerUserService.onConnected();
                }
            } else {
                mService.onDisconnected();
                writeServiceEvent(
                        FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__ON_DISCONNECTED,
                        mComponentName);
                EventLog.writeEvent(EventLogTags.CC_CONNECT_STATE_CHANGED,
                        mPerUserService.getUserId(),
                        ContentCapturePerUserService.EVENT_LOG_CONNECT_STATE_DISCONNECTED, 0);
            }
        } catch (Exception e) {
            Slog.w(mTag, "Exception calling onConnectedStateChanged(" + connected + "): " + e);
        }
    }

    public void ensureBoundLocked() {
        scheduleBind();
    }

    /**
     * Called by {@link ContentCaptureServerSession} to generate a call to the
     * {@link RemoteContentCaptureService} to indicate the session was created.
     */
    public void onSessionStarted(@Nullable ContentCaptureContext context, int sessionId, int uid,
            @NonNull IResultReceiver clientReceiver, int initialState) {
        scheduleAsyncRequest(
                (s) -> s.onSessionStarted(context, sessionId, uid, clientReceiver, initialState));
        // Metrics logging.
        writeSessionEvent(sessionId,
                FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS__EVENT__ON_SESSION_STARTED,
                initialState, getComponentName(), /* is_child_session= */ false);
    }

    /**
     * Called by {@link ContentCaptureServerSession} to generate a call to the
     * {@link RemoteContentCaptureService} to indicate the session was finished.
     */
    public void onSessionFinished(int sessionId) {
        scheduleAsyncRequest((s) -> s.onSessionFinished(sessionId));
        // Metrics logging.
        writeSessionEvent(sessionId,
                FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS__EVENT__ON_SESSION_FINISHED,
                /* flags= */ 0, getComponentName(), /* is_child_session= */ false);
    }

    /**
     * Called by {@link ContentCaptureServerSession} to send snapshot data to the service.
     */
    public void onActivitySnapshotRequest(int sessionId, @NonNull SnapshotData snapshotData) {
        scheduleAsyncRequest((s) -> s.onActivitySnapshot(sessionId, snapshotData));
    }

    /**
     * Called by {@link ContentCaptureServerSession} to request removal of user data.
     */
    public void onDataRemovalRequest(@NonNull DataRemovalRequest request) {
        scheduleAsyncRequest((s) -> s.onDataRemovalRequest(request));
        writeServiceEvent(
                FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__ON_USER_DATA_REMOVED,
                mComponentName);
    }

    public void onDataShareRequest(@NonNull DataShareRequest request,
            @NonNull IDataShareCallback.Stub dataShareCallback) {
        scheduleAsyncRequest((s) -> s.onDataShared(request, dataShareCallback));
        writeServiceEvent(
                FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__ON_DATA_SHARE_REQUEST,
                mComponentName);
    }

    /**
     * Called by {@link ContentCaptureServerSession} to notify a high-level activity event.
     */
    public void onActivityLifecycleEvent(@NonNull ActivityEvent event) {
        scheduleAsyncRequest((s) -> s.onActivityEvent(event));
    }

    public interface ContentCaptureServiceCallbacks
            extends VultureCallback<RemoteContentCaptureService> {
        // NOTE: so far we don't need to notify the callback implementation
        // (ContentCaptureServerSession) of the request results (success, timeouts, etc..), so this
        // callback interface is empty.
    }
}
