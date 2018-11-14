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

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.intelligence.InteractionSessionId;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.intelligence.ContentCaptureEvent;
import android.view.intelligence.IntelligenceManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.AbstractPerUserSystemService;

import java.io.PrintWriter;
import java.util.List;

/**
 * Per-user instance of {@link IntelligenceManagerService}.
 */
final class IntelligencePerUserService
        extends AbstractPerUserSystemService<IntelligencePerUserService> {

    private static final String TAG = "IntelligencePerUserService";

    @GuardedBy("mLock")
    private final ArrayMap<InteractionSessionId, ContentCaptureSession> mSessions =
            new ArrayMap<>();

    // TODO(b/111276913): add mechanism to prune stale sessions, similar to Autofill's

    protected IntelligencePerUserService(
            IntelligenceManagerService master, Object lock, int userId) {
        super(master, lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfo(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {

        ServiceInfo si;
        try {
            // TODO(b/111276913): must check that either the service is from a system component,
            // or it matches a service set by shell cmd (so it can be used on CTS tests and when
            // OEMs are implementing the real service
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not get service for " + serviceComponent + ": " + e);
            return null;
        }
        if (!Manifest.permission.BIND_INTELLIGENCE_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "IntelligenceService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_INTELLIGENCE_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_INTELLIGENCE_SERVICE);
        }
        return si;
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void startSessionLocked(@NonNull IBinder activityToken,
            @NonNull ComponentName componentName, int taskId, int displayId,
            @NonNull InteractionSessionId sessionId, int flags,
            @NonNull IResultReceiver resultReceiver) {
        if (!isEnabledLocked()) {
            sendToClient(resultReceiver, IntelligenceManager.STATE_DISABLED);
            return;
        }
        final ComponentName serviceComponentName = getServiceComponentName();
        if (serviceComponentName == null) {
            // TODO(b/111276913): this happens when the system service is starting, we should
            // probably handle it in a more elegant way (like waiting for boot_complete or
            // something like that
            if (mMaster.debug) {
                Slog.d(TAG, "startSession(" + activityToken + "): hold your horses");
            }
            return;
        }

        ContentCaptureSession session = mSessions.get(sessionId);
        if (session != null) {
            if (mMaster.debug) {
                Slog.d(TAG, "startSession(): reusing session " + sessionId + " for "
                        + componentName);
            }
            // TODO(b/111276913): check if local ids match and decide what to do if they don't
            // TODO(b/111276913): should we call session.notifySessionStartedLocked() again??
            // if not, move notifySessionStartedLocked() into session constructor
            sendToClient(resultReceiver, IntelligenceManager.STATE_ACTIVE);
            return;
        }

        // TODO(b/117779333): get from mMaster once it's moved to superclass
        final boolean bindInstantServiceAllowed = false;

        session = new ContentCaptureSession(getContext(), mUserId, mLock, activityToken,
                this, serviceComponentName, componentName, taskId, displayId, sessionId, flags,
                bindInstantServiceAllowed, mMaster.verbose);
        if (mMaster.verbose) {
            Slog.v(TAG, "startSession(): new session for " + componentName + " and id "
                    + sessionId);
        }
        mSessions.put(sessionId, session);
        session.notifySessionStartedLocked();
        sendToClient(resultReceiver, IntelligenceManager.STATE_ACTIVE);
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void finishSessionLocked(@NonNull InteractionSessionId sessionId) {
        if (!isEnabledLocked()) {
            return;
        }

        final ContentCaptureSession session = mSessions.get(sessionId);
        if (session == null) {
            if (mMaster.debug) {
                Slog.d(TAG, "finishSession(): no session with id" + sessionId);
            }
            return;
        }
        if (mMaster.verbose) {
            Slog.v(TAG, "finishSession(): " + session);
        }
        session.removeSelfLocked(true);
    }

    // TODO(b/111276913): need to figure out why some events are sent before session is started;
    // probably because IntelligenceManager is not buffering them until it gets the session back
    @GuardedBy("mLock")
    public void sendEventsLocked(@NonNull InteractionSessionId sessionId,
            @NonNull List<ContentCaptureEvent> events) {
        if (!isEnabledLocked()) {
            return;
        }
        final ContentCaptureSession session = mSessions.get(sessionId);
        if (session == null) {
            if (mMaster.verbose) {
                Slog.v(TAG, "sendEvents(): no session for " + sessionId);
            }
            return;
        }
        if (mMaster.verbose) {
            Slog.v(TAG, "sendEvents(): id=" + sessionId + "; events =" + events.size());
        }
        session.sendEventsLocked(events);
    }

    @GuardedBy("mLock")
    public void removeSessionLocked(@NonNull InteractionSessionId sessionId) {
        mSessions.remove(sessionId);
    }

    @GuardedBy("mLock")
    public boolean isIntelligenceServiceForUserLocked(int uid) {
        return uid == getServiceUidLocked();
    }

    /**
     * Destroys the service and all state associated with it.
     *
     * <p>Called when the service was disabled (for example, if the settings change).
     */
    @GuardedBy("mLock")
    public void destroyLocked() {
        if (mMaster.debug) Slog.d(TAG, "destroyLocked()");
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureSession session = mSessions.valueAt(i);
            session.destroyLocked(true);
        }
        mSessions.clear();
    }

    @Override
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        if (mSessions.isEmpty()) {
            pw.print(prefix); pw.println("no sessions");
        } else {
            final int size = mSessions.size();
            pw.print(prefix); pw.print("number sessions: "); pw.println(size);
            final String prefix2 = prefix + "  ";
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print("session@"); pw.println(i);
                final ContentCaptureSession session = mSessions.valueAt(i);
                session.dumpLocked(prefix2, pw);
            }
        }
    }

    private static void sendToClient(@NonNull IResultReceiver resultReceiver, int value) {
        try {
            resultReceiver.send(value, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }
}
