/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.prediction;

import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;

import static java.util.Collections.reverseOrder;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionManager;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.server.people.data.ConversationInfo;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.EventHistory;
import com.android.server.people.data.PackageData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Predictor that predicts the {@link AppTarget} the user is most likely to open on share sheet.
 */
class ShareTargetPredictor extends AppTargetPredictor {

    private static final String TAG = "ShareTargetPredictor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String REMOTE_APP_PREDICTOR_KEY = "remote_app_predictor";
    private final IntentFilter mIntentFilter;
    private final AppPredictor mRemoteAppPredictor;

    ShareTargetPredictor(@NonNull AppPredictionContext predictionContext,
            @NonNull Consumer<List<AppTarget>> updatePredictionsMethod,
            @NonNull DataManager dataManager,
            @UserIdInt int callingUserId, @NonNull Context context) {
        super(predictionContext, updatePredictionsMethod, dataManager, callingUserId);
        mIntentFilter = predictionContext.getExtras().getParcelable(
                ChooserActivity.APP_PREDICTION_INTENT_FILTER_KEY);
        if (DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DARK_LAUNCH_REMOTE_PREDICTION_SERVICE_ENABLED,
                false)) {
            predictionContext.getExtras().putBoolean(REMOTE_APP_PREDICTOR_KEY, true);
            mRemoteAppPredictor = context.createContextAsUser(UserHandle.of(callingUserId), 0)
                    .getSystemService(AppPredictionManager.class)
                    .createAppPredictionSession(predictionContext);
        } else {
            mRemoteAppPredictor = null;
        }
    }

    /** Reports chosen history of direct/app share targets. */
    @WorkerThread
    @Override
    void reportAppTargetEvent(AppTargetEvent event) {
        if (DEBUG) {
            Slog.d(TAG, "reportAppTargetEvent");
        }
        if (mIntentFilter != null) {
            getDataManager().reportShareTargetEvent(event, mIntentFilter);
        }
        if (mRemoteAppPredictor != null) {
            mRemoteAppPredictor.notifyAppTargetEvent(event);
        }
    }

    /** Provides prediction on direct share targets */
    @WorkerThread
    @Override
    void predictTargets() {
        if (DEBUG) {
            Slog.d(TAG, "predictTargets");
        }
        if (mIntentFilter == null) {
            updatePredictions(List.of());
            return;
        }
        List<ShareTarget> shareTargets = getDirectShareTargets();
        SharesheetModelScorer.computeScore(shareTargets, getShareEventType(mIntentFilter),
                System.currentTimeMillis());
        Collections.sort(shareTargets,
                Comparator.comparing(ShareTarget::getScore, reverseOrder())
                        .thenComparing(t -> t.getAppTarget().getRank()));
        List<AppTarget> res = new ArrayList<>();
        for (int i = 0; i < Math.min(getPredictionContext().getPredictedTargetCount(),
                shareTargets.size()); i++) {
            res.add(shareTargets.get(i).getAppTarget());
        }
        updatePredictions(res);
    }

    /** Provides prediction on app share targets */
    @WorkerThread
    @Override
    void sortTargets(List<AppTarget> targets, Consumer<List<AppTarget>> callback) {
        if (DEBUG) {
            Slog.d(TAG, "sortTargets");
        }
        if (mIntentFilter == null) {
            callback.accept(targets);
            return;
        }
        List<ShareTarget> shareTargets = getAppShareTargets(targets);
        SharesheetModelScorer.computeScoreForAppShare(shareTargets,
                getShareEventType(mIntentFilter), getPredictionContext().getPredictedTargetCount(),
                System.currentTimeMillis(), getDataManager(),
                mCallingUserId);
        Collections.sort(shareTargets, (t1, t2) -> -Float.compare(t1.getScore(), t2.getScore()));
        List<AppTarget> appTargetList = new ArrayList<>();
        for (ShareTarget shareTarget : shareTargets) {
            AppTarget appTarget = shareTarget.getAppTarget();
            appTargetList.add(new AppTarget.Builder(appTarget.getId(), appTarget.getPackageName(),
                    appTarget.getUser())
                    .setClassName(appTarget.getClassName())
                    .setRank(shareTarget.getScore() > 0 ? (int) (shareTarget.getScore()
                            * 1000) : 0)
                    .build());
        }
        callback.accept(appTargetList);
    }

    /** Recycles resources. */
    @WorkerThread
    @Override
    void destroy() {
        if (mRemoteAppPredictor != null) {
            mRemoteAppPredictor.destroy();
        }
    }

    private List<ShareTarget> getDirectShareTargets() {
        List<ShareTarget> shareTargets = new ArrayList<>();
        List<ShareShortcutInfo> shareShortcuts =
                getDataManager().getShareShortcuts(mIntentFilter, mCallingUserId);

        for (ShareShortcutInfo shareShortcut : shareShortcuts) {
            ShortcutInfo shortcutInfo = shareShortcut.getShortcutInfo();
            AppTarget appTarget = new AppTarget.Builder(
                    new AppTargetId(shortcutInfo.getId()),
                    shortcutInfo)
                    .setClassName(shareShortcut.getTargetComponent().getClassName())
                    .setRank(shortcutInfo.getRank())
                    .build();
            String packageName = shortcutInfo.getPackage();
            int userId = shortcutInfo.getUserId();
            PackageData packageData = getDataManager().getPackage(packageName, userId);

            ConversationInfo conversationInfo = null;
            EventHistory eventHistory = null;
            if (packageData != null) {
                String shortcutId = shortcutInfo.getId();
                conversationInfo = packageData.getConversationInfo(shortcutId);
                if (conversationInfo != null) {
                    eventHistory = packageData.getEventHistory(shortcutId);
                }
            }
            shareTargets.add(new ShareTarget(appTarget, eventHistory, conversationInfo));
        }

        return shareTargets;
    }

    private List<ShareTarget> getAppShareTargets(List<AppTarget> targets) {
        List<ShareTarget> shareTargets = new ArrayList<>();
        for (AppTarget target : targets) {
            PackageData packageData = getDataManager().getPackage(target.getPackageName(),
                    target.getUser().getIdentifier());
            shareTargets.add(new ShareTarget(target,
                    packageData == null ? null
                            : packageData.getClassLevelEventHistory(target.getClassName()), null));
        }
        return shareTargets;
    }

    private int getShareEventType(IntentFilter intentFilter) {
        String mimeType = intentFilter != null ? intentFilter.getDataType(0) : null;
        return getDataManager().mimeTypeToShareEventType(mimeType);
    }

    @VisibleForTesting
    static class ShareTarget {

        @NonNull
        private final AppTarget mAppTarget;
        @Nullable
        private final EventHistory mEventHistory;
        @Nullable
        private final ConversationInfo mConversationInfo;
        private float mScore;

        @VisibleForTesting
        ShareTarget(@NonNull AppTarget appTarget,
                @Nullable EventHistory eventHistory,
                @Nullable ConversationInfo conversationInfo) {
            mAppTarget = appTarget;
            mEventHistory = eventHistory;
            mConversationInfo = conversationInfo;
            mScore = 0f;
        }

        @NonNull
        @VisibleForTesting
        AppTarget getAppTarget() {
            return mAppTarget;
        }

        @Nullable
        @VisibleForTesting
        EventHistory getEventHistory() {
            return mEventHistory;
        }

        @Nullable
        @VisibleForTesting
        ConversationInfo getConversationInfo() {
            return mConversationInfo;
        }

        @VisibleForTesting
        float getScore() {
            return mScore;
        }

        @VisibleForTesting
        void setScore(float score) {
            mScore = score;
        }
    }
}
