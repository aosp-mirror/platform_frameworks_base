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
import static android.view.contentcapture.ContentCaptureSession.STATE_DISABLED;
import static android.view.contentcapture.ContentCaptureSession.STATE_DUPLICATED_ID;
import static android.view.contentcapture.ContentCaptureSession.STATE_INTERNAL_ERROR;
import static android.view.contentcapture.ContentCaptureSession.STATE_NOT_WHITELISTED;
import static android.view.contentcapture.ContentCaptureSession.STATE_NO_SERVICE;

import static com.android.server.contentcapture.ContentCaptureMetricsLogger.writeServiceEvent;
import static com.android.server.contentcapture.ContentCaptureMetricsLogger.writeSessionEvent;
import static com.android.server.contentcapture.ContentCaptureMetricsLogger.writeSetWhitelistEvent;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_DATA;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.assist.ActivityId;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.pm.ActivityPresentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.contentcapture.ActivityEvent;
import android.service.contentcapture.ActivityEvent.ActivityEventType;
import android.service.contentcapture.ContentCaptureService;
import android.service.contentcapture.ContentCaptureServiceInfo;
import android.service.contentcapture.FlushMetrics;
import android.service.contentcapture.IContentCaptureServiceCallback;
import android.service.contentcapture.IDataShareCallback;
import android.service.contentcapture.SnapshotData;
import android.service.voice.VoiceInteractionManagerInternal;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.contentcapture.ContentCaptureCondition;
import android.view.contentcapture.DataRemovalRequest;
import android.view.contentcapture.DataShareRequest;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.contentcapture.RemoteContentCaptureService.ContentCaptureServiceCallbacks;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-user instance of {@link ContentCaptureManagerService}.
 */
final class ContentCapturePerUserService
        extends
        AbstractPerUserSystemService<ContentCapturePerUserService, ContentCaptureManagerService>
        implements ContentCaptureServiceCallbacks {

    private static final String TAG = ContentCapturePerUserService.class.getSimpleName();

    @GuardedBy("mLock")
    private final SparseArray<ContentCaptureServerSession> mSessions = new SparseArray<>();

    /**
     * Reference to the remote service.
     *
     * <p>It's set in the constructor, but it's also updated when the service's updated in the
     * main service's cache (for example, because a temporary service was set).
     */
    @GuardedBy("mLock")
    @Nullable
    RemoteContentCaptureService mRemoteService;

    private final ContentCaptureServiceRemoteCallback mRemoteServiceCallback =
            new ContentCaptureServiceRemoteCallback();

    /**
     * List of conditions keyed by package.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, ArraySet<ContentCaptureCondition>> mConditionsByPkg =
            new ArrayMap<>();

    /**
     * When {@code true}, remote service died but service state is kept so it's restored after
     * the system re-binds to it.
     */
    @GuardedBy("mLock")
    private boolean mZombie;

    @GuardedBy("mLock")
    private ContentCaptureServiceInfo mInfo;

    // TODO(b/111276913): add mechanism to prune stale sessions, similar to Autofill's

    ContentCapturePerUserService(@NonNull ContentCaptureManagerService master,
            @NonNull Object lock, boolean disabled, @UserIdInt int userId) {
        super(master, lock, userId);
        updateRemoteServiceLocked(disabled);
    }

    /**
     * Updates the reference to the remote service.
     */
    private void updateRemoteServiceLocked(boolean disabled) {
        if (mMaster.verbose) Slog.v(TAG, "updateRemoteService(disabled=" + disabled + ")");
        if (mRemoteService != null) {
            if (mMaster.debug) Slog.d(TAG, "updateRemoteService(): destroying old remote service");
            mRemoteService.destroy();
            mRemoteService = null;
            resetContentCaptureWhitelistLocked();
        }

        // Updates the component name
        final ComponentName serviceComponentName = updateServiceInfoLocked();

        if (serviceComponentName == null) {
            if (mMaster.debug) Slog.d(TAG, "updateRemoteService(): no service component name");
            return;
        }

        if (!disabled) {
            if (mMaster.debug) {
                Slog.d(TAG, "updateRemoteService(): creating new remote service for "
                        + serviceComponentName);
            }
            mRemoteService = new RemoteContentCaptureService(mMaster.getContext(),
                    ContentCaptureService.SERVICE_INTERFACE, serviceComponentName,
                    mRemoteServiceCallback, mUserId, this, mMaster.isBindInstantServiceAllowed(),
                    mMaster.verbose, mMaster.mDevCfgIdleUnbindTimeoutMs);
        }
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {
        mInfo = new ContentCaptureServiceInfo(getContext(), serviceComponent,
                isTemporaryServiceSetLocked(), mUserId);
        return mInfo.getServiceInfo();
    }

    @Override // from PerUserSystemService
    @GuardedBy("mLock")
    protected boolean updateLocked(boolean disabled) {
        final boolean disabledStateChanged = super.updateLocked(disabled);
        if (disabledStateChanged) {
            // update session content capture enabled state.
            for (int i = 0; i < mSessions.size(); i++) {
                mSessions.valueAt(i).setContentCaptureEnabledLocked(!disabled);
            }
        }
        destroyLocked();
        updateRemoteServiceLocked(disabled);
        return disabledStateChanged;
    }

    @Override // from ContentCaptureServiceCallbacks
    public void onServiceDied(@NonNull RemoteContentCaptureService service) {
        // Don't do anything; eventually the system will bind to it again...
        Slog.w(TAG, "remote service died: " + service);
        synchronized (mLock) {
            mZombie = true;
        }
    }

    /**
     * Called after the remote service connected, it's used to restore state from a 'zombie'
     * service (i.e., after it died).
     */
    void onConnected() {
        synchronized (mLock) {
            if (mZombie) {
                // Validity check - shouldn't happen
                if (mRemoteService == null) {
                    Slog.w(TAG, "Cannot ressurect sessions because remote service is null");
                    return;
                }

                mZombie = false;
                resurrectSessionsLocked();
            }
        }
    }

    private void resurrectSessionsLocked() {
        final int numSessions = mSessions.size();
        if (mMaster.debug) {
            Slog.d(TAG, "Ressurrecting remote service (" + mRemoteService + ") on "
                    + numSessions + " sessions");
        }

        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            session.resurrectLocked();
        }
    }

    void onPackageUpdatingLocked() {
        final int numSessions = mSessions.size();
        if (mMaster.debug) {
            Slog.d(TAG, "Pausing " + numSessions + " sessions while package is updating");
        }
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            session.pauseLocked();
        }
    }

    void onPackageUpdatedLocked() {
        updateRemoteServiceLocked(!isEnabledLocked());
        resurrectSessionsLocked();
    }

    @GuardedBy("mLock")
    public void startSessionLocked(@NonNull IBinder activityToken,
            @NonNull IBinder shareableActivityToken,
            @NonNull ActivityPresentationInfo activityPresentationInfo, int sessionId, int uid,
            int flags, @NonNull IResultReceiver clientReceiver) {
        if (activityPresentationInfo == null) {
            Slog.w(TAG, "basic activity info is null");
            setClientState(clientReceiver, STATE_DISABLED | STATE_INTERNAL_ERROR,
                    /* binder= */ null);
            return;
        }
        final int taskId = activityPresentationInfo.taskId;
        final int displayId = activityPresentationInfo.displayId;
        final ComponentName componentName = activityPresentationInfo.componentName;
        final boolean whiteListed = mMaster.mGlobalContentCaptureOptions.isWhitelisted(mUserId,
                componentName) || mMaster.mGlobalContentCaptureOptions.isWhitelisted(mUserId,
                        componentName.getPackageName());
        final ComponentName serviceComponentName = getServiceComponentName();
        final boolean enabled = isEnabledLocked();
        if (mMaster.mRequestsHistory != null) {
            final String historyItem =
                    "id=" + sessionId + " uid=" + uid
                    + " a=" + ComponentName.flattenToShortString(componentName)
                    + " t=" + taskId + " d=" + displayId
                    + " s=" + ComponentName.flattenToShortString(serviceComponentName)
                    + " u=" + mUserId + " f=" + flags + (enabled ? "" : " (disabled)")
                    + " w=" + whiteListed;
            mMaster.mRequestsHistory.log(historyItem);
        }

        if (!enabled) {
            // TODO: it would be better to split in differet reasons, like
            // STATE_DISABLED_NO and STATE_DISABLED_BY_DEVICE_POLICY
            setClientState(clientReceiver, STATE_DISABLED | STATE_NO_SERVICE,
                    /* binder= */ null);
            // Log metrics.
            writeSessionEvent(sessionId,
                    FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS__EVENT__SESSION_NOT_CREATED,
                    STATE_DISABLED | STATE_NO_SERVICE, serviceComponentName,
                    componentName, /* isChildSession= */ false);
            return;
        }
        if (serviceComponentName == null) {
            // TODO(b/111276913): this happens when the system service is starting, we should
            // probably handle it in a more elegant way (like waiting for boot_complete or
            // something like that
            if (mMaster.debug) {
                Slog.d(TAG, "startSession(" + activityToken + "): hold your horses");
            }
            return;
        }

        if (!whiteListed) {
            if (mMaster.debug) {
                Slog.d(TAG, "startSession(" + componentName + "): package or component "
                        + "not whitelisted");
            }
            setClientState(clientReceiver, STATE_DISABLED | STATE_NOT_WHITELISTED,
                    /* binder= */ null);
            // Log metrics.
            writeSessionEvent(sessionId,
                    FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS__EVENT__SESSION_NOT_CREATED,
                    STATE_DISABLED | STATE_NOT_WHITELISTED, serviceComponentName,
                    componentName, /* isChildSession= */ false);
            return;
        }

        final ContentCaptureServerSession existingSession = mSessions.get(sessionId);
        if (existingSession != null) {
            Slog.w(TAG, "startSession(id=" + existingSession + ", token=" + activityToken
                    + ": ignoring because it already exists for " + existingSession.mActivityToken);
            setClientState(clientReceiver, STATE_DISABLED | STATE_DUPLICATED_ID,
                    /* binder=*/ null);
            // Log metrics.
            writeSessionEvent(sessionId,
                    FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS__EVENT__SESSION_NOT_CREATED,
                    STATE_DISABLED | STATE_DUPLICATED_ID,
                    serviceComponentName, componentName, /* isChildSession= */ false);
            return;
        }

        if (mRemoteService == null) {
            updateRemoteServiceLocked(/* disabled= */ false); // already checked for isEnabled
        }

        if (mRemoteService == null) {
            Slog.w(TAG, "startSession(id=" + existingSession + ", token=" + activityToken
                    + ": ignoring because service is not set");
            setClientState(clientReceiver, STATE_DISABLED | STATE_NO_SERVICE,
                    /* binder= */ null);
            // Log metrics.
            writeSessionEvent(sessionId,
                    FrameworkStatsLog.CONTENT_CAPTURE_SESSION_EVENTS__EVENT__SESSION_NOT_CREATED,
                    STATE_DISABLED | STATE_NO_SERVICE, serviceComponentName,
                    componentName, /* isChildSession= */ false);
            return;
        }

        // Make sure service is bound, just in case the initial connection failed somehow
        mRemoteService.ensureBoundLocked();

        final ContentCaptureServerSession newSession = new ContentCaptureServerSession(mLock,
                activityToken, new ActivityId(taskId, shareableActivityToken), this, componentName,
                clientReceiver, taskId, displayId, sessionId, uid, flags);
        if (mMaster.verbose) {
            Slog.v(TAG, "startSession(): new session for "
                    + ComponentName.flattenToShortString(componentName) + " and id " + sessionId);
        }
        mSessions.put(sessionId, newSession);
        newSession.notifySessionStartedLocked(clientReceiver);
    }

    @GuardedBy("mLock")
    public void finishSessionLocked(int sessionId) {
        if (!isEnabledLocked()) {
            return;
        }

        final ContentCaptureServerSession session = mSessions.get(sessionId);
        if (session == null) {
            if (mMaster.debug) {
                Slog.d(TAG, "finishSession(): no session with id" + sessionId);
            }
            return;
        }
        if (mMaster.verbose) Slog.v(TAG, "finishSession(): id=" + sessionId);
        session.removeSelfLocked(/* notifyRemoteService= */ true);
    }

    @GuardedBy("mLock")
    public void removeDataLocked(@NonNull DataRemovalRequest request) {
        if (!isEnabledLocked()) {
            return;
        }
        assertCallerLocked(request.getPackageName());
        mRemoteService.onDataRemovalRequest(request);
    }

    @GuardedBy("mLock")
    public void onDataSharedLocked(@NonNull DataShareRequest request,
            IDataShareCallback.Stub dataShareCallback) {
        if (!isEnabledLocked()) {
            return;
        }
        assertCallerLocked(request.getPackageName());
        mRemoteService.onDataShareRequest(request, dataShareCallback);
    }

    @GuardedBy("mLock")
    @Nullable
    public ComponentName getServiceSettingsActivityLocked() {
        if (mInfo == null) return null;

        final String activityName = mInfo.getSettingsActivity();
        if (activityName == null) return null;

        final String packageName = mInfo.getServiceInfo().packageName;
        return new ComponentName(packageName, activityName);
    }

    /**
     * Asserts the component is owned by the caller.
     */
    @GuardedBy("mLock")
    private void assertCallerLocked(@NonNull String packageName) {
        final PackageManager pm = getContext().getPackageManager();
        final int callingUid = Binder.getCallingUid();
        final int packageUid;
        try {
            packageUid = pm.getPackageUidAsUser(packageName, UserHandle.getCallingUserId());
        } catch (NameNotFoundException e) {
            throw new SecurityException("Could not verify UID for " + packageName);
        }
        if (callingUid != packageUid && !LocalServices.getService(ActivityManagerInternal.class)
                .hasRunningActivity(callingUid, packageName)) {

            VoiceInteractionManagerInternal.HotwordDetectionServiceIdentity
                    hotwordDetectionServiceIdentity =
                    LocalServices.getService(VoiceInteractionManagerInternal.class)
                            .getHotwordDetectionServiceIdentity();

            boolean isHotwordDetectionServiceCall =
                    hotwordDetectionServiceIdentity != null
                            && callingUid == hotwordDetectionServiceIdentity.getIsolatedUid()
                            && packageUid == hotwordDetectionServiceIdentity.getOwnerUid();

            if (!isHotwordDetectionServiceCall) {
                final String[] packages = pm.getPackagesForUid(callingUid);
                final String callingPackage = packages != null ? packages[0] : "uid-" + callingUid;
                Slog.w(TAG, "App (package=" + callingPackage + ", UID=" + callingUid
                        + ") passed package (" + packageName + ") owned by UID " + packageUid);

                throw new SecurityException("Invalid package: " + packageName);
            }
        }
    }

    @GuardedBy("mLock")
    public boolean sendActivityAssistDataLocked(@NonNull IBinder activityToken,
            @NonNull Bundle data) {
        final int id = getSessionId(activityToken);
        final Bundle assistData = data.getBundle(ASSIST_KEY_DATA);
        final AssistStructure assistStructure = data.getParcelable(ASSIST_KEY_STRUCTURE);
        final AssistContent assistContent = data.getParcelable(ASSIST_KEY_CONTENT);
        final SnapshotData snapshotData = new SnapshotData(assistData,
                assistStructure, assistContent);
        if (id != NO_SESSION_ID) {
            final ContentCaptureServerSession session = mSessions.get(id);
            session.sendActivitySnapshotLocked(snapshotData);
            return true;
        }

        // We want to send an activity snapshot regardless of whether a content capture session is
        // present or not since a content capture session is not required for this functionality
        if (mRemoteService != null) {
            mRemoteService.onActivitySnapshotRequest(NO_SESSION_ID, snapshotData);
            Slog.d(TAG, "Notified activity assist data for activity: "
                    + activityToken + " without a session Id");
            return true;
        }

        return false;
    }

    @GuardedBy("mLock")
    public void removeSessionLocked(int sessionId) {
        mSessions.remove(sessionId);
    }

    @GuardedBy("mLock")
    public boolean isContentCaptureServiceForUserLocked(int uid) {
        return uid == getServiceUidLocked();
    }

    @GuardedBy("mLock")
    private ContentCaptureServerSession getSession(@NonNull IBinder activityToken) {
        for (int i = 0; i < mSessions.size(); i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
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
        if (mRemoteService != null) {
            mRemoteService.destroy();
        }
        destroySessionsLocked();
    }

    @GuardedBy("mLock")
    void destroySessionsLocked() {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            session.destroyLocked(/* notifyRemoteService= */ true);
        }
        mSessions.clear();
    }

    @GuardedBy("mLock")
    void listSessionsLocked(ArrayList<String> output) {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            output.add(session.toShortString());
        }
    }

    @GuardedBy("mLock")
    @Nullable
    ArraySet<ContentCaptureCondition> getContentCaptureConditionsLocked(
            @NonNull String packageName) {
        return mConditionsByPkg.get(packageName);
    }

    @GuardedBy("mLock")
    void onActivityEventLocked(@NonNull ComponentName componentName, @ActivityEventType int type) {
        if (mRemoteService == null) {
            if (mMaster.debug) Slog.d(mTag, "onActivityEvent(): no remote service");
            return;
        }
        final ActivityEvent event = new ActivityEvent(componentName, type);

        if (mMaster.verbose) Slog.v(mTag, "onActivityEvent(): " + event);

        mRemoteService.onActivityLifecycleEvent(event);
    }

    @Override
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);

        final String prefix2 = prefix + "  ";
        pw.print(prefix); pw.print("Service Info: ");
        if (mInfo == null) {
            pw.println("N/A");
        } else {
            pw.println();
            mInfo.dump(prefix2, pw);
        }
        pw.print(prefix); pw.print("Zombie: "); pw.println(mZombie);

        if (mRemoteService != null) {
            pw.print(prefix); pw.println("remote service:");
            mRemoteService.dump(prefix2, pw);
        }

        if (mSessions.size() == 0) {
            pw.print(prefix); pw.println("no sessions");
        } else {
            final int sessionsSize = mSessions.size();
            pw.print(prefix); pw.print("number sessions: "); pw.println(sessionsSize);
            for (int i = 0; i < sessionsSize; i++) {
                pw.print(prefix); pw.print("#"); pw.println(i);
                final ContentCaptureServerSession session = mSessions.valueAt(i);
                session.dumpLocked(prefix2, pw);
                pw.println();
            }
        }
    }

    /**
     * Returns the session id associated with the given activity.
     */
    @GuardedBy("mLock")
    private int getSessionId(@NonNull IBinder activityToken) {
        for (int i = 0; i < mSessions.size(); i++) {
            ContentCaptureServerSession session = mSessions.valueAt(i);
            if (session.isActivitySession(activityToken)) {
                return mSessions.keyAt(i);
            }
        }
        return NO_SESSION_ID;
    }

    /**
     * Resets the content capture allowlist.
     */
    @GuardedBy("mLock")
    private void resetContentCaptureWhitelistLocked() {
        if (mMaster.verbose) {
            Slog.v(TAG, "resetting content capture whitelist");
        }
        mMaster.mGlobalContentCaptureOptions.resetWhitelist(mUserId);
    }

    private final class ContentCaptureServiceRemoteCallback extends
            IContentCaptureServiceCallback.Stub {

        @Override
        public void setContentCaptureWhitelist(List<String> packages,
                List<ComponentName> activities) {
            // TODO(b/122595322): add CTS test for when it's null
            if (mMaster.verbose) {
                Slog.v(TAG, "setContentCaptureWhitelist(" + (packages == null
                        ? "null_packages" : packages.size() + " packages")
                        + ", " + (activities == null
                        ? "null_activities" : activities.size() + " activities") + ")"
                        + " for user " + mUserId);
            }

            ArraySet<String> oldList =
                    mMaster.mGlobalContentCaptureOptions.getWhitelistedPackages(mUserId);

            mMaster.mGlobalContentCaptureOptions.setWhitelist(mUserId, packages, activities);
            writeSetWhitelistEvent(getServiceComponentName(), packages, activities);

            updateContentCaptureOptions(oldList);

            // Must disable session that are not the allowlist anymore...
            final int numSessions = mSessions.size();
            if (numSessions <= 0) return;

            // ...but without holding the lock on mGlobalContentCaptureOptions
            final SparseBooleanArray blacklistedSessions = new SparseBooleanArray(numSessions);

            for (int i = 0; i < numSessions; i++) {
                final ContentCaptureServerSession session = mSessions.valueAt(i);
                final boolean whitelisted = mMaster.mGlobalContentCaptureOptions
                        .isWhitelisted(mUserId, session.appComponentName);
                if (!whitelisted) {
                    final int sessionId = mSessions.keyAt(i);
                    if (mMaster.debug) {
                        Slog.d(TAG, "marking session " + sessionId + " (" + session.appComponentName
                                + ") for un-whitelisting");
                    }
                    blacklistedSessions.append(sessionId, true);
                }
            }
            final int numBlacklisted = blacklistedSessions.size();

            if (numBlacklisted <= 0) return;

            synchronized (mLock) {
                for (int i = 0; i < numBlacklisted; i++) {
                    final int sessionId = blacklistedSessions.keyAt(i);
                    if (mMaster.debug) Slog.d(TAG, "un-whitelisting " + sessionId);
                    final ContentCaptureServerSession session = mSessions.get(sessionId);
                    session.setContentCaptureEnabledLocked(false);
                }
            }
        }

        @Override
        public void setContentCaptureConditions(String packageName,
                List<ContentCaptureCondition> conditions) {
            if (mMaster.verbose) {
                Slog.v(TAG, "setContentCaptureConditions(" + packageName + "): "
                        + (conditions == null ? "null" : conditions.size() + " conditions"));
            }
            synchronized (mLock) {
                if (conditions == null) {
                    mConditionsByPkg.remove(packageName);
                } else {
                    mConditionsByPkg.put(packageName, new ArraySet<>(conditions));
                }
            }
        }

        @Override
        public void disableSelf() {
            if (mMaster.verbose) Slog.v(TAG, "disableSelf()");

            final long token = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putStringForUser(getContext().getContentResolver(),
                        Settings.Secure.CONTENT_CAPTURE_ENABLED, "0", mUserId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            writeServiceEvent(FrameworkStatsLog.CONTENT_CAPTURE_SERVICE_EVENTS__EVENT__SET_DISABLED,
                    getServiceComponentName());
        }

        @Override
        public void writeSessionFlush(int sessionId, ComponentName app, FlushMetrics flushMetrics,
                ContentCaptureOptions options, int flushReason) {
            ContentCaptureMetricsLogger.writeSessionFlush(sessionId, getServiceComponentName(), app,
                    flushMetrics, options, flushReason);
        }

        /** Updates {@link ContentCaptureOptions} for all newly added packages on allowlist. */
        private void updateContentCaptureOptions(@Nullable ArraySet<String> oldList) {
            ArraySet<String> adding = mMaster.mGlobalContentCaptureOptions
                    .getWhitelistedPackages(mUserId);

            if (oldList != null && adding != null) {
                adding.removeAll(oldList);
            }

            int N = adding != null ? adding.size() : 0;
            for (int i = 0; i < N; i++) {
                String packageName = adding.valueAt(i);
                ContentCaptureOptions options = mMaster.mGlobalContentCaptureOptions
                        .getOptions(mUserId, packageName);
                mMaster.updateOptions(packageName, options);
            }
        }
    }
}
