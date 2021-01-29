/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.people;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IPeopleManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.people.data.DataManager;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A service that manages the people and conversations provided by apps.
 */
public class PeopleService extends SystemService {

    private static final String TAG = "PeopleService";

    private final DataManager mDataManager;

    private PackageManagerInternal mPackageManagerInternal;

    /**
     * Initializes the system service.
     *
     * @param context The system server context.
     */
    public PeopleService(Context context) {
        super(context);

        mDataManager = new DataManager(context);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mDataManager.initialize();
        }
    }

    @Override
    public void onStart() {
        onStart(/* isForTesting= */ false);
    }

    @VisibleForTesting
    protected void onStart(boolean isForTesting) {
        if (!isForTesting) {
            publishBinderService(Context.PEOPLE_SERVICE, mService);
        }
        publishLocalService(PeopleServiceInternal.class, new LocalService());
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mDataManager.onUserUnlocked(user.getUserIdentifier());
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        mDataManager.onUserStopping(user.getUserIdentifier());
    }

    /**
     * Enforces that only the system or root UID can make certain calls.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static void enforceSystemOrRoot(String message) {
        if (!isSystemOrRoot()) {
            throw new SecurityException("Only system may " + message);
        }
    }

    private static boolean isSystemOrRoot() {
        final int uid = Binder.getCallingUid();
        return UserHandle.isSameApp(uid, Process.SYSTEM_UID) || uid == Process.ROOT_UID;
    }

    private int handleIncomingUser(int userId) {
        try {
            return ActivityManager.getService().handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, "", null);
        } catch (RemoteException re) {
            // Shouldn't happen, local.
        }
        return userId;
    }

    private void checkCallerIsSameApp(String pkg) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        if (mPackageManagerInternal.getPackageUid(pkg, /*flags=*/ 0,
                callingUserId) != callingUid) {
            throw new SecurityException("Calling uid " + callingUid + " cannot query events"
                    + "for package " + pkg);
        }
    }

    /**
     * Enforces that only the system, root UID or SystemUI can make certain calls.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static void enforceSystemRootOrSystemUI(Context context, String message) {
        if (isSystemOrRoot()) return;
        context.enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                message);
    }

    final IBinder mService = new IPeopleManager.Stub() {

        @Override
        public ConversationChannel getConversation(
                String packageName, int userId, String shortcutId) {
            enforceSystemRootOrSystemUI(getContext(), "get conversation");
            return mDataManager.getConversation(packageName, userId, shortcutId);
        }

        @Override
        public ParceledListSlice<ConversationChannel> getRecentConversations() {
            enforceSystemRootOrSystemUI(getContext(), "get recent conversations");
            return new ParceledListSlice<>(
                    mDataManager.getRecentConversations(
                            Binder.getCallingUserHandle().getIdentifier()));
        }

        @Override
        public void removeRecentConversation(String packageName, int userId, String shortcutId) {
            enforceSystemOrRoot("remove a recent conversation");
            mDataManager.removeRecentConversation(packageName, userId, shortcutId,
                    Binder.getCallingUserHandle().getIdentifier());
        }

        @Override
        public void removeAllRecentConversations() {
            enforceSystemOrRoot("remove all recent conversations");
            mDataManager.removeAllRecentConversations(
                    Binder.getCallingUserHandle().getIdentifier());
        }

        @Override
        public long getLastInteraction(String packageName, int userId, String shortcutId) {
            enforceSystemRootOrSystemUI(getContext(), "get last interaction");
            return mDataManager.getLastInteraction(packageName, userId, shortcutId);
        }

        @Override
        public void addOrUpdateStatus(String packageName, int userId, String conversationId,
                ConversationStatus status) {
            handleIncomingUser(userId);
            checkCallerIsSameApp(packageName);
            if (status.getStartTimeMillis() > System.currentTimeMillis()) {
                throw new IllegalArgumentException("Start time must be in the past");
            }
            mDataManager.addOrUpdateStatus(packageName, userId, conversationId, status);
        }

        @Override
        public void clearStatus(String packageName, int userId, String conversationId,
                String statusId) {
            handleIncomingUser(userId);
            checkCallerIsSameApp(packageName);
            mDataManager.clearStatus(packageName, userId, conversationId, statusId);
        }

        @Override
        public void clearStatuses(String packageName, int userId, String conversationId) {
            handleIncomingUser(userId);
            checkCallerIsSameApp(packageName);
            mDataManager.clearStatuses(packageName, userId, conversationId);
        }

        @Override
        public ParceledListSlice<ConversationStatus> getStatuses(String packageName, int userId,
                String conversationId) {
            handleIncomingUser(userId);
            if (!isSystemOrRoot()) {
                checkCallerIsSameApp(packageName);
            }
            return new ParceledListSlice<>(
                    mDataManager.getStatuses(packageName, userId, conversationId));
        }
    };

    @VisibleForTesting
    final class LocalService extends PeopleServiceInternal {

        private Map<AppPredictionSessionId, SessionInfo> mSessions = new ArrayMap<>();

        @Override
        public void onCreatePredictionSession(AppPredictionContext context,
                AppPredictionSessionId sessionId) {
            mSessions.put(sessionId, new SessionInfo(context, mDataManager, sessionId.getUserId()));
        }

        @Override
        public void notifyAppTargetEvent(AppPredictionSessionId sessionId, AppTargetEvent event) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onAppTargetEvent(event));
        }

        @Override
        public void notifyLaunchLocationShown(AppPredictionSessionId sessionId,
                String launchLocation, ParceledListSlice targetIds) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onLaunchLocationShown(
                            launchLocation, targetIds.getList()));
        }

        @Override
        public void sortAppTargets(AppPredictionSessionId sessionId, ParceledListSlice targets,
                IPredictionCallback callback) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onSortAppTargets(
                            targets.getList(),
                            targetList -> invokePredictionCallback(callback, targetList)));
        }

        @Override
        public void registerPredictionUpdates(AppPredictionSessionId sessionId,
                IPredictionCallback callback) {
            runForSession(sessionId, sessionInfo -> sessionInfo.addCallback(callback));
        }

        @Override
        public void unregisterPredictionUpdates(AppPredictionSessionId sessionId,
                IPredictionCallback callback) {
            runForSession(sessionId, sessionInfo -> sessionInfo.removeCallback(callback));
        }

        @Override
        public void requestPredictionUpdate(AppPredictionSessionId sessionId) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onRequestPredictionUpdate());
        }

        @Override
        public void onDestroyPredictionSession(AppPredictionSessionId sessionId) {
            runForSession(sessionId, sessionInfo -> {
                sessionInfo.onDestroy();
                mSessions.remove(sessionId);
            });
        }

        @Override
        public void pruneDataForUser(@UserIdInt int userId, @NonNull CancellationSignal signal) {
            mDataManager.pruneDataForUser(userId, signal);
        }

        @Nullable
        @Override
        public byte[] getBackupPayload(@UserIdInt int userId) {
            return mDataManager.getBackupPayload(userId);
        }

        @Override
        public void restore(@UserIdInt int userId, @NonNull byte[] payload) {
            mDataManager.restore(userId, payload);
        }

        @VisibleForTesting
        SessionInfo getSessionInfo(AppPredictionSessionId sessionId) {
            return mSessions.get(sessionId);
        }

        private void runForSession(AppPredictionSessionId sessionId, Consumer<SessionInfo> method) {
            SessionInfo sessionInfo = mSessions.get(sessionId);
            if (sessionInfo == null) {
                Slog.e(TAG, "Failed to find the session: " + sessionId);
                return;
            }
            method.accept(sessionInfo);
        }

        private void invokePredictionCallback(IPredictionCallback callback,
                List<AppTarget> targets) {
            try {
                callback.onResult(new ParceledListSlice<>(targets));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to calling callback" + e);
            }
        }
    }
}
