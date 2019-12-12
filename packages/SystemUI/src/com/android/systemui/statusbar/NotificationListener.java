/*
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.RemoteInputController.processForRemoteInput;
import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;
import static com.android.systemui.statusbar.phone.StatusBar.DEBUG;
import static com.android.systemui.statusbar.phone.StatusBar.ENABLE_CHILD_NOTIFICATIONS;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.MainHandler;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationListenerWithPlugins;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class handles listening to notification updates and passing them along to
 * NotificationPresenter to be displayed to the user.
 */
@SuppressLint("OverrideAbstract")
@Singleton
public class NotificationListener extends NotificationListenerWithPlugins {
    private static final String TAG = "NotificationListener";

    // Dependencies:
    private final NotificationEntryManager mEntryManager;
    private final NotificationGroupManager mGroupManager;

    private final Context mContext;
    private final Handler mMainHandler;
    private final ArrayList<NotificationSettingsListener> mSettingsListeners = new ArrayList<>();
    @Nullable private NotifServiceListener mDownstreamListener;

    @Inject
    public NotificationListener(Context context, @MainHandler Handler mainHandler,
            NotificationEntryManager notificationEntryManager,
            NotificationGroupManager notificationGroupManager) {
        mContext = context;
        mMainHandler = mainHandler;
        mEntryManager = notificationEntryManager;
        mGroupManager = notificationGroupManager;
    }

    public void addNotificationSettingsListener(NotificationSettingsListener listener) {
        mSettingsListeners.add(listener);
    }

    public void setDownstreamListener(NotifServiceListener downstreamListener) {
        mDownstreamListener = downstreamListener;
    }

    @Override
    public void onListenerConnected() {
        if (DEBUG) Log.d(TAG, "onListenerConnected");
        onPluginConnected();
        final StatusBarNotification[] notifications = getActiveNotifications();
        if (notifications == null) {
            Log.w(TAG, "onListenerConnected unable to get active notifications.");
            return;
        }
        final RankingMap currentRanking = getCurrentRanking();
        mMainHandler.post(() -> {
            // There's currently a race condition between the calls to getActiveNotifications() and
            // getCurrentRanking(). It's possible for the ranking that we store here to not contain
            // entries for every notification in getActiveNotifications(). To prevent downstream
            // crashes, we temporarily fill in these missing rankings with stubs.
            // See b/146011844 for long-term fix
            final List<Ranking> newRankings = new ArrayList<>();
            for (StatusBarNotification sbn : notifications) {
                newRankings.add(getRankingOrTemporaryStandIn(currentRanking, sbn.getKey()));
            }
            final RankingMap completeMap = new RankingMap(newRankings.toArray(new Ranking[0]));

            for (StatusBarNotification sbn : notifications) {
                if (mDownstreamListener != null) {
                    mDownstreamListener.onNotificationPosted(sbn, completeMap);
                }
                mEntryManager.addNotification(sbn, completeMap);
            }
        });
        NotificationManager noMan = mContext.getSystemService(NotificationManager.class);
        onSilentStatusBarIconsVisibilityChanged(noMan.shouldHideSilentStatusBarIcons());
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn,
            final RankingMap rankingMap) {
        if (DEBUG) Log.d(TAG, "onNotificationPosted: " + sbn);
        if (sbn != null && !onPluginNotificationPosted(sbn, rankingMap)) {
            mMainHandler.post(() -> {
                processForRemoteInput(sbn.getNotification(), mContext);

                if (mDownstreamListener != null) {
                    mDownstreamListener.onNotificationPosted(sbn, rankingMap);
                }

                String key = sbn.getKey();
                boolean isUpdate = mEntryManager.getActiveNotificationUnfiltered(key) != null;
                // In case we don't allow child notifications, we ignore children of
                // notifications that have a summary, since` we're not going to show them
                // anyway. This is true also when the summary is canceled,
                // because children are automatically canceled by NoMan in that case.
                if (!ENABLE_CHILD_NOTIFICATIONS
                        && mGroupManager.isChildInGroupWithSummary(sbn)) {
                    if (DEBUG) {
                        Log.d(TAG, "Ignoring group child due to existing summary: " + sbn);
                    }

                    // Remove existing notification to avoid stale data.
                    if (isUpdate) {
                        mEntryManager.removeNotification(key, rankingMap, UNDEFINED_DISMISS_REASON);
                    } else {
                        mEntryManager.updateRanking(rankingMap, "onNotificationPosted");
                    }
                    return;
                }
                if (isUpdate) {
                    mEntryManager.updateNotification(sbn, rankingMap);
                } else {
                    mEntryManager.addNotification(sbn, rankingMap);
                }
            });
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            int reason) {
        if (DEBUG) Log.d(TAG, "onNotificationRemoved: " + sbn + " reason: " + reason);
        if (sbn != null && !onPluginNotificationRemoved(sbn, rankingMap)) {
            final String key = sbn.getKey();
            mMainHandler.post(() -> {
                if (mDownstreamListener != null) {
                    mDownstreamListener.onNotificationRemoved(sbn, rankingMap, reason);
                }
                mEntryManager.removeNotification(key, rankingMap, reason);
            });
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        onNotificationRemoved(sbn, rankingMap, UNDEFINED_DISMISS_REASON);
    }

    @Override
    public void onNotificationRankingUpdate(final RankingMap rankingMap) {
        if (DEBUG) Log.d(TAG, "onRankingUpdate");
        if (rankingMap != null) {
            RankingMap r = onPluginRankingUpdate(rankingMap);
            mMainHandler.post(() -> {
                if (mDownstreamListener != null) {
                    mDownstreamListener.onNotificationRankingUpdate(rankingMap);
                }
                mEntryManager.updateNotificationRanking(r);
            });
        }
    }

    @Override
    public void onSilentStatusBarIconsVisibilityChanged(boolean hideSilentStatusIcons) {
        for (NotificationSettingsListener listener : mSettingsListeners) {
            listener.onStatusBarIconsBehaviorChanged(hideSilentStatusIcons);
        }
    }

    public void registerAsSystemService() {
        try {
            registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register notification listener", e);
        }
    }

    private static Ranking getRankingOrTemporaryStandIn(RankingMap rankingMap, String key) {
        Ranking ranking = new Ranking();
        if (!rankingMap.getRanking(key, ranking)) {
            ranking.populate(
                    key,
                    0,
                    false,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    false,
                    0,
                    false,
                    0,
                    false,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    false,
                    false
            );
        }
        return ranking;
    }

    public interface NotificationSettingsListener {

        default void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) { }
    }

    /** Interface for listening to add/remove events that we receive from NotificationManager. */
    public interface NotifServiceListener {
        void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap);
        void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap);
        void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason);
        void onNotificationRankingUpdate(RankingMap rankingMap);
    }
}
