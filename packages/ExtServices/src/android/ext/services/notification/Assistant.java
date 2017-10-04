/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEGATIVE;

import android.app.INotificationManager;
import android.content.Context;
import android.ext.services.R;
import android.os.Bundle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;

/**
 * Notification assistant that provides guidance on notification channel blocking
 */
public class Assistant extends NotificationAssistantService {
    private static final String TAG = "ExtAssistant";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final ArrayList<Integer> DISMISS_WITH_PREJUDICE = new ArrayList<>();
    static {
        DISMISS_WITH_PREJUDICE.add(REASON_CANCEL);
        DISMISS_WITH_PREJUDICE.add(REASON_LISTENER_CANCEL);
    }

    // key : impressions tracker
    // TODO: persist across reboots
    ArrayMap<String, ChannelImpressions> mkeyToImpressions = new ArrayMap<>();
    // SBN key : channel id
    ArrayMap<String, String> mLiveNotifications = new ArrayMap<>();

    private Ranking mFakeRanking = null;

    @Override
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn) {
        if (DEBUG) Log.i(TAG, "ENQUEUED " + sbn.getKey());
        return null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (DEBUG) Log.i(TAG, "POSTED " + sbn.getKey());
        try {
            Ranking ranking = getRanking(sbn.getKey(), rankingMap);
            if (ranking != null && ranking.getChannel() != null) {
                String key = getKey(
                        sbn.getPackageName(), sbn.getUserId(), ranking.getChannel().getId());
                ChannelImpressions ci = mkeyToImpressions.getOrDefault(key,
                        new ChannelImpressions());
                if (ranking.getImportance() > IMPORTANCE_MIN && ci.shouldTriggerBlock()) {
                    adjustNotification(createNegativeAdjustment(
                            sbn.getPackageName(), sbn.getKey(), sbn.getUserId()));
                }
                mkeyToImpressions.put(key, ci);
                mLiveNotifications.put(sbn.getKey(), ranking.getChannel().getId());
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error occurred processing post", e);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            NotificationStats stats, int reason) {
        try {
            String channelId = mLiveNotifications.remove(sbn.getKey());
            String key = getKey(sbn.getPackageName(), sbn.getUserId(), channelId);
            ChannelImpressions ci = mkeyToImpressions.getOrDefault(key, new ChannelImpressions());
            if (stats.hasSeen()) {
                ci.incrementViews();
            }
            if (DISMISS_WITH_PREJUDICE.contains(reason)
                    && !sbn.isAppGroup()
                    && !sbn.getNotification().isGroupChild()
                    && !stats.hasInteracted()
                    && stats.getDismissalSurface() != NotificationStats.DISMISSAL_AOD
                    && stats.getDismissalSurface() != NotificationStats.DISMISSAL_PEEK
                    && stats.getDismissalSurface() != NotificationStats.DISMISSAL_OTHER) {
               if (DEBUG) Log.i(TAG, "increment dismissals");
                ci.incrementDismissals();
            } else {
                if (DEBUG) Slog.i(TAG, "reset streak");
                ci.resetStreak();
            }
            mkeyToImpressions.put(key, ci);
        } catch (Throwable e) {
            Slog.e(TAG, "Error occurred processing removal", e);
        }
    }

    @Override
    public void onNotificationSnoozedUntilContext(StatusBarNotification sbn,
            String snoozeCriterionId) {
    }

    @Override
    public void onListenerConnected() {
        if (DEBUG) Log.i(TAG, "CONNECTED");
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) {
                onNotificationPosted(sbn);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error occurred on connection", e);
        }
    }

    private String getKey(String pkg, int userId, String channelId) {
        return pkg + "|" + userId + "|" + channelId;
    }

    private Ranking getRanking(String key, RankingMap rankingMap) {
        if (mFakeRanking != null) {
            return mFakeRanking;
        }
        Ranking ranking = new Ranking();
        rankingMap.getRanking(key, ranking);
        return ranking;
    }

    private Adjustment createNegativeAdjustment(String packageName, String key, int user) {
        if (DEBUG) Log.d(TAG, "User probably doesn't want " + key);
        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEGATIVE);
        return new Adjustment(packageName, key,  signals,
                getContext().getString(R.string.prompt_block_reason), user);
    }

    // for testing
    protected void setFakeRanking(Ranking ranking) {
        mFakeRanking = ranking;
    }

    protected void setNoMan(INotificationManager noMan) {
        mNoMan = noMan;
    }

    protected void setContext(Context context) {
        mSystemContext = context;
    }
}