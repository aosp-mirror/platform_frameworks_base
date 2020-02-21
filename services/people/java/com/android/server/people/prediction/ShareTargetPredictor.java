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
import android.util.Range;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.server.people.data.ConversationInfo;
import com.android.server.people.data.DataManager;
import com.android.server.people.data.Event;
import com.android.server.people.data.EventHistory;
import com.android.server.people.data.PackageData;

import java.util.ArrayList;
import java.util.Collections;
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

    /** Reports chosen history of direct/app share targets. */
    @WorkerThread
    @Override
    void reportAppTargetEvent(AppTargetEvent event) {
        getDataManager().reportShareTargetEvent(event, mIntentFilter);
    }

    /** Provides prediction on direct share targets */
    @WorkerThread
    @Override
    void predictTargets() {
        List<ShareTarget> shareTargets = getDirectShareTargets();
        rankTargets(shareTargets);
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
        List<ShareTarget> shareTargets = getAppShareTargets(targets);
        rankTargets(shareTargets);
        List<AppTarget> appTargetList = new ArrayList<>();
        shareTargets.forEach(t -> appTargetList.add(t.getAppTarget()));
        callback.accept(appTargetList);
    }

    private void rankTargets(List<ShareTarget> shareTargets) {
        // Rank targets based on recency of sharing history only for the moment.
        // TODO: Take more factors into ranking, e.g. frequency, mime type, foreground app.
        Collections.sort(shareTargets, (t1, t2) -> {
            if (t1.getEventHistory() == null) {
                return 1;
            }
            if (t2.getEventHistory() == null) {
                return -1;
            }
            Range<Long> timeSlot1 = t1.getEventHistory().getEventIndex(
                    Event.SHARE_EVENT_TYPES).getMostRecentActiveTimeSlot();
            Range<Long> timeSlot2 = t2.getEventHistory().getEventIndex(
                    Event.SHARE_EVENT_TYPES).getMostRecentActiveTimeSlot();
            if (timeSlot1 == null) {
                return 1;
            } else if (timeSlot2 == null) {
                return -1;
            } else {
                return -Long.compare(timeSlot1.getUpper(), timeSlot2.getUpper());
            }
        });
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

    @VisibleForTesting
    static class ShareTarget {

        @NonNull
        private final AppTarget mAppTarget;
        @Nullable
        private final EventHistory mEventHistory;
        @Nullable
        private final ConversationInfo mConversationInfo;

        private ShareTarget(@NonNull AppTarget appTarget,
                @Nullable EventHistory eventHistory,
                @Nullable ConversationInfo conversationInfo) {
            mAppTarget = appTarget;
            mEventHistory = eventHistory;
            mConversationInfo = conversationInfo;
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
    }
}
