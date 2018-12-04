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

import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_DATA;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.intelligence.InteractionSessionId;
import android.service.intelligence.SnapshotData;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import android.view.intelligence.ContentCaptureEvent;
import android.view.intelligence.ContentCaptureManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.intelligence.IntelligenceManagerInternal.AugmentedAutofillCallback;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-user instance of {@link IntelligenceManagerService}.
 */
//TODO(b/111276913): rename once the final name is defined
final class IntelligencePerUserService
        extends AbstractPerUserSystemService<IntelligencePerUserService,
            IntelligenceManagerService> {

    private static final String TAG = "IntelligencePerUserService";

    @GuardedBy("mLock")
    private final ArrayMap<InteractionSessionId, ContentCaptureSession> mSessions =
            new ArrayMap<>();

    // TODO(b/111276913): add mechanism to prune stale sessions, similar to Autofill's

    protected IntelligencePerUserService(
            IntelligenceManagerService master, Object lock, @UserIdInt int userId) {
        super(master, new FrameworkResourcesServiceNameResolver(master.getContext(), userId, lock,
                com.android.internal.R.string.config_defaultSmartSuggestionsService), lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfo(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {

        int flags = PackageManager.GET_META_DATA;
        final boolean isTemp = isTemporaryServiceSetLocked();
        if (!isTemp) {
            flags |= PackageManager.MATCH_SYSTEM_ONLY;
        }

        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, flags, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not get service for " + serviceComponent + ": " + e);
            return null;
        }
        if (si == null) {
            Slog.w(TAG, "Could not get serviceInfo for " + (isTemp ? " (temp)" : "(default system)")
                    + " " + serviceComponent.flattenToShortString());
            return null;
        }
        if (!Manifest.permission.BIND_SMART_SUGGESTIONS_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "SmartSuggestionsService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_SMART_SUGGESTIONS_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_SMART_SUGGESTIONS_SERVICE);
        }
        return si;
    }

    @Override // from PerUserSystemService
    @GuardedBy("mLock")
    protected boolean updateLocked(boolean disabled) {
        destroyLocked();
        return super.updateLocked(disabled);
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void startSessionLocked(@NonNull IBinder activityToken,
            @NonNull ComponentName componentName, int taskId, int displayId,
            @NonNull InteractionSessionId sessionId, int flags, boolean bindInstantServiceAllowed,
            @NonNull IResultReceiver resultReceiver) {
        if (!isEnabledLocked()) {
            sendToClient(resultReceiver, ContentCaptureManager.STATE_DISABLED);
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
            sendToClient(resultReceiver, ContentCaptureManager.STATE_ACTIVE);
            return;
        }

        session = new ContentCaptureSession(getContext(), mUserId, mLock, activityToken,
                this, serviceComponentName, componentName, taskId, displayId, sessionId, flags,
                bindInstantServiceAllowed, mMaster.verbose);
        if (mMaster.verbose) {
            Slog.v(TAG, "startSession(): new session for " + componentName + " and id "
                    + sessionId);
        }
        mSessions.put(sessionId, session);
        session.notifySessionStartedLocked();
        sendToClient(resultReceiver, ContentCaptureManager.STATE_ACTIVE);
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void finishSessionLocked(@NonNull InteractionSessionId sessionId,
            @Nullable List<ContentCaptureEvent> events) {
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
        if (events != null && !events.isEmpty()) {
            // TODO(b/111276913): for now we're sending the events and the onDestroy() in 2 separate
            // calls because it's not clear yet whether we'll change the manager to send events
            // to the service directly (i.e., without passing through system server). Once we
            // decide, we might need to split IIntelligenceService.onSessionLifecycle() in 2
            // methods, one for start and another for finish (and passing the events to finish),
            // otherwise the service might receive the 2 calls out of order.
            session.sendEventsLocked(events);
        }
        if (mMaster.verbose) {
            Slog.v(TAG, "finishSession(" + (events == null ? 0 : events.size()) + " events): "
                    + session);
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
            Slog.v(TAG, "sendEvents(): id=" + sessionId + ", events=" + events.size());
        }
        session.sendEventsLocked(events);
    }

    @GuardedBy("mLock")
    public boolean sendActivityAssistDataLocked(@NonNull IBinder activityToken,
            @NonNull Bundle data) {
        final InteractionSessionId id = getInteractionSessionId(activityToken);
        if (id != null) {
            final ContentCaptureSession session = mSessions.get(id);
            final Bundle assistData = data.getBundle(ASSIST_KEY_DATA);
            final AssistStructure assistStructure = data.getParcelable(ASSIST_KEY_STRUCTURE);
            final AssistContent assistContent = data.getParcelable(ASSIST_KEY_CONTENT);
            final SnapshotData snapshotData = new SnapshotData(assistData,
                    assistStructure, assistContent);
            session.sendActivitySnapshotLocked(snapshotData);
            return true;
        } else {
            Slog.e(TAG, "Failed to notify activity assist data for activity: " + activityToken);
        }
        return false;
    }

    @GuardedBy("mLock")
    public void removeSessionLocked(@NonNull InteractionSessionId sessionId) {
        mSessions.remove(sessionId);
    }

    @GuardedBy("mLock")
    public boolean isIntelligenceServiceForUserLocked(int uid) {
        return uid == getServiceUidLocked();
    }

    @GuardedBy("mLock")
    private ContentCaptureSession getSession(@NonNull IBinder activityToken) {
        for (int i = 0; i < mSessions.size(); i++) {
            final ContentCaptureSession session = mSessions.valueAt(i);
            if (session.mActivityToken.equals(activityToken)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Destroys the service and all state associated with it.
     *
     * <p>Called when the service was disabled (for example, if the settings change).
     */
    @GuardedBy("mLock")
    public void destroyLocked() {
        if (mMaster.debug) Slog.d(TAG, "destroyLocked()");
        destroySessionsLocked();
    }

    @GuardedBy("mLock")
    void destroySessionsLocked() {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureSession session = mSessions.valueAt(i);
            session.destroyLocked(true);
        }
        mSessions.clear();
    }

    @GuardedBy("mLock")
    void listSessionsLocked(ArrayList<String> output) {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureSession session = mSessions.valueAt(i);
            output.add(session.toShortString());
        }
    }

    public AugmentedAutofillCallback requestAutofill(@NonNull IAutoFillManagerClient client,
            @NonNull IBinder activityToken, int autofillSessionId, @NonNull AutofillId focusedId,
            @Nullable AutofillValue focusedValue) {
        synchronized (mLock) {
            final ContentCaptureSession session = getSession(activityToken);
            if (session != null) {
                // TODO(b/111330312): log metrics
                if (mMaster.verbose) Slog.v(TAG, "requestAugmentedAutofill()");
                return session.requestAutofillLocked(client, autofillSessionId, focusedId,
                        focusedValue);
            }
            if (mMaster.debug) {
                Slog.d(TAG, "requestAutofill(): no session for " + activityToken);
            }
            return null;
        }
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

    /**
     * Returns the InteractionSessionId associated with the given activity.
     */
    @GuardedBy("mLock")
    private InteractionSessionId getInteractionSessionId(@NonNull IBinder activityToken) {
        for (int i = 0; i < mSessions.size(); i++) {
            ContentCaptureSession session = mSessions.valueAt(i);
            if (session.isActivitySession(activityToken)) {
                return mSessions.keyAt(i);
            }
        }
        return null;
    }

    private static void sendToClient(@NonNull IResultReceiver resultReceiver, int value) {
        try {
            resultReceiver.send(value, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error async reporting result to client: " + e);
        }
    }
}
