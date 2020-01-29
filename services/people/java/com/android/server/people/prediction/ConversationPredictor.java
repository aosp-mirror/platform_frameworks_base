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

package com.android.server.people.prediction;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.EventHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Predictor that predicts the conversations or apps the user is most likely to open.
 */
public class ConversationPredictor {

    private static final String UI_SURFACE_SHARE = "share";

    private final AppPredictionContext mPredictionContext;
    private final Consumer<List<AppTarget>> mUpdatePredictionsMethod;
    private final DataManager mDataManager;
    private final ExecutorService mCallbackExecutor;
    @Nullable
    private final IntentFilter mIntentFilter;

    public ConversationPredictor(@NonNull AppPredictionContext predictionContext,
            @NonNull Consumer<List<AppTarget>> updatePredictionsMethod,
            @NonNull DataManager dataManager) {
        mPredictionContext = predictionContext;
        mUpdatePredictionsMethod = updatePredictionsMethod;
        mDataManager = dataManager;
        mCallbackExecutor = Executors.newSingleThreadExecutor();
        if (UI_SURFACE_SHARE.equals(mPredictionContext.getUiSurface())) {
            mIntentFilter = mPredictionContext.getExtras().getParcelable(
                    ChooserActivity.APP_PREDICTION_INTENT_FILTER_KEY);
        } else {
            mIntentFilter = null;
        }
    }

    /**
     * Called by the client app to indicate a target launch.
     */
    @MainThread
    public void onAppTargetEvent(AppTargetEvent event) {
        mDataManager.reportAppTargetEvent(event, mIntentFilter);
    }

    /**
     * Called by the client app to indicate a particular location has been shown to the user.
     */
    @MainThread
    public void onLaunchLocationShown(String launchLocation, List<AppTargetId> targetIds) {}

    /**
     * Called by the client app to request sorting of the provided targets based on the prediction
     * ranking.
     */
    @MainThread
    public void onSortAppTargets(List<AppTarget> targets, Consumer<List<AppTarget>> callback) {
        mCallbackExecutor.execute(() -> callback.accept(targets));
    }

    /**
     * Called by the client app to request target predictions.
     */
    @MainThread
    public void onRequestPredictionUpdate() {
        // TODO: Re-route the call to different ranking classes for different surfaces.
        mCallbackExecutor.execute(() -> {
            List<AppTarget> targets = new ArrayList<>();
            if (mIntentFilter != null) {
                List<ShareShortcutInfo> shareShortcuts =
                        mDataManager.getConversationShareTargets(mIntentFilter);
                for (ShareShortcutInfo shareShortcut : shareShortcuts) {
                    ShortcutInfo shortcutInfo = shareShortcut.getShortcutInfo();
                    AppTargetId appTargetId = new AppTargetId(shortcutInfo.getId());
                    String shareTargetClass = shareShortcut.getTargetComponent().getClassName();
                    targets.add(new AppTarget.Builder(appTargetId, shortcutInfo)
                            .setClassName(shareTargetClass)
                            .build());
                }
            } else {
                List<ConversationData> conversationDataList = new ArrayList<>();
                mDataManager.forAllPackages(packageData ->
                        packageData.forAllConversations(conversationInfo -> {
                            EventHistory eventHistory = packageData.getEventHistory(
                                    conversationInfo.getShortcutId());
                            ConversationData conversationData = new ConversationData(
                                    packageData.getPackageName(), packageData.getUserId(),
                                    conversationInfo, eventHistory);
                            conversationDataList.add(conversationData);
                        }));
                for (ConversationData conversationData : conversationDataList) {
                    String shortcutId = conversationData.getConversationInfo().getShortcutId();
                    ShortcutInfo shortcut = mDataManager.getShortcut(
                            conversationData.getPackageName(), conversationData.getUserId(),
                            shortcutId);
                    if (shortcut != null) {
                        AppTargetId appTargetId = new AppTargetId(shortcut.getId());
                        targets.add(new AppTarget.Builder(appTargetId, shortcut).build());
                    }
                }
            }
            mUpdatePredictionsMethod.accept(targets);
        });
    }

    @VisibleForTesting
    public Consumer<List<AppTarget>> getUpdatePredictionsMethod() {
        return mUpdatePredictionsMethod;
    }
}
