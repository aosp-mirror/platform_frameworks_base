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
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
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
        publishLocalService(PeopleServiceInternal.class, new LocalService());
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mDataManager.onUserUnlocked(user.getUserIdentifier());
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        mDataManager.onUserStopping(user.getUserIdentifier());
    }

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
