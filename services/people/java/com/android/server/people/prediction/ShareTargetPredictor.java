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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.server.people.data.ConversationInfo;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.EventHistory;
import com.android.server.people.data.PackageData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class ShareTargetPredictor extends AppTargetPredictor {

    private final IntentFilter mIntentFilter;

    ShareTargetPredictor(@NonNull AppPredictionContext predictionContext,
            @NonNull Consumer<List<AppTarget>> updatePredictionsMethod,
            @NonNull DataManager dataManager, @UserIdInt int callingUserId) {
        super(predictionContext, updatePredictionsMethod, dataManager, callingUserId);
        mIntentFilter = predictionContext.getExtras().getParcelable(
                ChooserActivity.APP_PREDICTION_INTENT_FILTER_KEY);
    }

    @MainThread
    @Override
    public void onAppTargetEvent(AppTargetEvent event) {
        getDataManager().reportAppTargetEvent(event, mIntentFilter);
    }

    @WorkerThread
    @Override
    protected void predictTargets() {
        List<ShareTarget> shareTargets = getShareTargets();
        // TODO: Rank the share targets with the data in ShareTarget.mConversationData.
        List<AppTarget> appTargets = new ArrayList<>();
        for (ShareTarget shareTarget : shareTargets) {

            ShortcutInfo shortcutInfo = shareTarget.getShareShortcutInfo().getShortcutInfo();
            AppTargetId appTargetId = new AppTargetId(shortcutInfo.getId());
            String shareTargetClassName =
                    shareTarget.getShareShortcutInfo().getTargetComponent().getClassName();
            AppTarget appTarget = new AppTarget.Builder(appTargetId, shortcutInfo)
                    .setClassName(shareTargetClassName)
                    .build();
            appTargets.add(appTarget);
            if (appTargets.size() >= getPredictionContext().getPredictedTargetCount()) {
                break;
            }
        }
        updatePredictions(appTargets);
    }

    @VisibleForTesting
    List<ShareTarget> getShareTargets() {
        List<ShareTarget> shareTargets = new ArrayList<>();
        List<ShareShortcutInfo> shareShortcuts =
                getDataManager().getShareShortcuts(mIntentFilter, mCallingUserId);

        for (ShareShortcutInfo shareShortcut : shareShortcuts) {
            ShortcutInfo shortcutInfo = shareShortcut.getShortcutInfo();
            String packageName = shortcutInfo.getPackage();
            int userId = shortcutInfo.getUserId();
            PackageData packageData = getDataManager().getPackage(packageName, userId);

            ConversationData conversationData = null;
            if (packageData != null) {
                String shortcutId = shortcutInfo.getId();
                ConversationInfo conversationInfo =
                        packageData.getConversationInfo(shortcutId);

                if (conversationInfo != null) {
                    EventHistory eventHistory = packageData.getEventHistory(shortcutId);
                    conversationData = new ConversationData(
                            packageName, userId, conversationInfo, eventHistory);
                }
            }
            shareTargets.add(new ShareTarget(shareShortcut, conversationData));
        }

        return shareTargets;
    }

    @VisibleForTesting
    static class ShareTarget {

        @NonNull
        private final ShareShortcutInfo mShareShortcutInfo;
        @Nullable
        private final ConversationData mConversationData;

        private ShareTarget(@NonNull ShareShortcutInfo shareShortcutInfo,
                @Nullable ConversationData conversationData) {
            mShareShortcutInfo = shareShortcutInfo;
            mConversationData = conversationData;
        }

        @NonNull
        @VisibleForTesting
        ShareShortcutInfo getShareShortcutInfo() {
            return mShareShortcutInfo;
        }

        @Nullable
        @VisibleForTesting
        ConversationData getConversationData() {
            return mConversationData;
        }
    }
}
