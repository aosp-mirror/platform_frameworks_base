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

import static android.service.contentcapture.ContentCaptureService.setClientState;
import static android.view.contentcapture.ContentCaptureManager.NO_SESSION_ID;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_FALSE;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_TRUE;
import static android.view.contentcapture.ContentCaptureSession.STATE_ACTIVE;
import static android.view.contentcapture.ContentCaptureSession.STATE_DISABLED;
import static android.view.contentcapture.ContentCaptureSession.STATE_SERVICE_RESURRECTED;
import static android.view.contentcapture.ContentCaptureSession.STATE_SERVICE_UPDATING;

import android.annotation.NonNull;
import android.app.assist.ActivityId;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.contentcapture.ContentCaptureService;
import android.service.contentcapture.SnapshotData;
import android.util.LocalLog;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureContext;
import android.view.contentcapture.ContentCaptureSessionId;
import android.view.contentcapture.MainContentCaptureSession;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

final class ContentCaptureServerSession {

    private static final String TAG = ContentCaptureServerSession.class.getSimpleName();

    final IBinder mActivityToken;
    private final ContentCapturePerUserService mService;

    // NOTE: this is the "internal" context (like package and taskId), not the explicit content
    // set by apps - those are only send to the ContentCaptureService.
    private final ContentCaptureContext mContentCaptureContext;

    /**
     * Reference to the binder object help at the client-side process and used to set its state.
     */
    @NonNull
    private final IResultReceiver mSessionStateReceiver;

    /**
     * Canonical session id.
     */
    private final int mId;

    /**
     * UID of the app whose contents is being captured.
     */
    private final int mUid;

    private final Object mLock;

    public final ComponentName appComponentName;

    ContentCaptureServerSession(@NonNull Object lock, @NonNull IBinder activityToken,
            @NonNull ActivityId activityId, @NonNull ContentCapturePerUserService service,
            @NonNull ComponentName appComponentName, @NonNull IResultReceiver sessionStateReceiver,
            int taskId, int displayId, int sessionId, int uid, int flags) {
        Preconditions.checkArgument(sessionId != NO_SESSION_ID);
        mLock = lock;
        mActivityToken = activityToken;
        this.appComponentName = appComponentName;
        mService = service;
        mId = sessionId;
        mUid = uid;
        mContentCaptureContext = new ContentCaptureContext(/* clientContext= */ null,
                activityId, appComponentName, displayId, activityToken, flags);
        mSessionStateReceiver = sessionStateReceiver;
        try {
            sessionStateReceiver.asBinder().linkToDeath(() -> onClientDeath(), 0);
        } catch (Exception e) {
            Slog.w(TAG, "could not register DeathRecipient for " + activityToken);
        }
    }

    /**
     * Returns whether this session is for the given activity.
     */
    boolean isActivitySession(@NonNull IBinder activityToken) {
        return mActivityToken.equals(activityToken);
    }

    /**
     * Notifies the {@link ContentCaptureService} that the service started.
     */
    @GuardedBy("mLock")
    public void notifySessionStartedLocked(@NonNull IResultReceiver clientReceiver) {
        if (mService.mRemoteService == null) {
            Slog.w(TAG, "notifySessionStartedLocked(): no remote service");
            return;
        }
        mService.mRemoteService.onSessionStarted(mContentCaptureContext, mId, mUid, clientReceiver,
                STATE_ACTIVE);
    }

    /**
     * Changes the {@link ContentCaptureService} enabled state.
     */
    @GuardedBy("mLock")
    public void setContentCaptureEnabledLocked(boolean enabled) {
        try {
            final Bundle extras = new Bundle();
            extras.putBoolean(MainContentCaptureSession.EXTRA_ENABLED_STATE, true);
            mSessionStateReceiver.send(enabled ? RESULT_CODE_TRUE : RESULT_CODE_FALSE, extras);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }

    /**
     * Notifies the {@link ContentCaptureService} of a snapshot of an activity.
     */
    @GuardedBy("mLock")
    public void sendActivitySnapshotLocked(@NonNull SnapshotData snapshotData) {
        final LocalLog logHistory = mService.getMaster().mRequestsHistory;
        if (logHistory != null) {
            logHistory.log("snapshot: id=" + mId);
        }

        if (mService.mRemoteService == null) {
            Slog.w(TAG, "sendActivitySnapshotLocked(): no remote service");
            return;
        }
        mService.mRemoteService.onActivitySnapshotRequest(mId, snapshotData);
    }

    /**
     * Cleans up the session and removes it from the service.
     *
     * @param notifyRemoteService whether it should trigger a {@link
     * ContentCaptureService#onDestroyContentCaptureSession(ContentCaptureSessionId)}
     * request.
     */
    @GuardedBy("mLock")
    public void removeSelfLocked(boolean notifyRemoteService) {
        try {
            destroyLocked(notifyRemoteService);
        } finally {
            mService.removeSessionLocked(mId);
        }
    }

    /**
     * Cleans up the session, but not removes it from the service.
     *
     * @param notifyRemoteService whether it should trigger a {@link
     * ContentCaptureService#onDestroyContentCaptureSession(ContentCaptureSessionId)}
     * request.
     */
    @GuardedBy("mLock")
    public void destroyLocked(boolean notifyRemoteService) {
        if (mService.isVerbose()) {
            Slog.v(TAG, "destroy(notifyRemoteService=" + notifyRemoteService + ")");
        }
        // TODO(b/111276913): must call client to set session as FINISHED_BY_SERVER
        if (notifyRemoteService) {
            if (mService.mRemoteService == null) {
                Slog.w(TAG, "destroyLocked(): no remote service");
                return;
            }
            mService.mRemoteService.onSessionFinished(mId);
        }
    }

    /**
     * Called to restore the active state of a session that was paused while the service died.
     */
    @GuardedBy("mLock")
    public void resurrectLocked() {
        final RemoteContentCaptureService remoteService = mService.mRemoteService;
        if (remoteService == null) {
            Slog.w(TAG, "destroyLocked(: no remote service");
            return;
        }
        if (mService.isVerbose()) {
            Slog.v(TAG, "resurrecting " + mActivityToken + " on " + remoteService);
        }
        remoteService.onSessionStarted(new ContentCaptureContext(mContentCaptureContext,
                ContentCaptureContext.FLAG_RECONNECTED), mId, mUid, mSessionStateReceiver,
                STATE_ACTIVE | STATE_SERVICE_RESURRECTED);
    }

    /**
     * Called to pause the session while the service is being updated.
     */
    @GuardedBy("mLock")
    public void pauseLocked() {
        if (mService.isVerbose()) Slog.v(TAG, "pausing " + mActivityToken);
        setClientState(mSessionStateReceiver, STATE_DISABLED | STATE_SERVICE_UPDATING,
                /* binder= */ null);
    }

    /**
     * Called when the session client binder object died - typically when its process was killed
     * and the activity was not properly destroyed.
     */
    private void onClientDeath() {
        if (mService.isVerbose()) {
            Slog.v(TAG, "onClientDeath(" + mActivityToken + "): removing session " + mId);
        }
        synchronized (mLock) {
            removeSelfLocked(/* notifyRemoteService= */ true);
        }
    }

    @GuardedBy("mLock")
    public void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("id: ");  pw.print(mId); pw.println();
        pw.print(prefix); pw.print("uid: ");  pw.print(mUid); pw.println();
        pw.print(prefix); pw.print("context: ");  mContentCaptureContext.dump(pw); pw.println();
        pw.print(prefix); pw.print("activity token: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("app component: "); pw.println(appComponentName);
        pw.print(prefix); pw.print("has autofill callback: ");
    }

    String toShortString() {
        return mId  + ":" + mActivityToken;
    }

    @Override
    public String toString() {
        return "ContentCaptureSession[id=" + mId + ", act=" + mActivityToken + "]";
    }
}
