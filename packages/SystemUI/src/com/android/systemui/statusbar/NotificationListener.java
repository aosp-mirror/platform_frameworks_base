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

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.dagger.StatusBarModule;
import com.android.systemui.statusbar.phone.NotificationListenerWithPlugins;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.ArrayList;
import java.util.List;

/**
 * This class handles listening to notification updates and passing them along to
 * NotificationPresenter to be displayed to the user.
 */
@SuppressLint("OverrideAbstract")
public class NotificationListener extends NotificationListenerWithPlugins {
    private static final String TAG = "NotificationListener";
    private static final boolean DEBUG = StatusBar.DEBUG;

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final Handler mMainHandler;
    private final List<NotificationHandler> mNotificationHandlers = new ArrayList<>();
    private final ArrayList<NotificationSettingsListener> mSettingsListeners = new ArrayList<>();

    /**
     * Injected constructor. See {@link StatusBarModule}.
     */
    public NotificationListener(
            Context context,
            NotificationManager notificationManager,
            @Main Handler mainHandler) {
        mContext = context;
        mNotificationManager = notificationManager;
        mMainHandler = mainHandler;
    }

    /** Registers a listener that's notified when notifications are added/removed/etc. */
    public void addNotificationHandler(NotificationHandler handler) {
        if (mNotificationHandlers.contains(handler)) {
            throw new IllegalArgumentException("Listener is already added");
        }
        mNotificationHandlers.add(handler);
    }

    /** Registers a listener that's notified when any notification-related settings change. */
    public void addNotificationSettingsListener(NotificationSettingsListener listener) {
        mSettingsListeners.add(listener);
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
                for (NotificationHandler listener : mNotificationHandlers) {
                    listener.onNotificationPosted(sbn, completeMap);
                }
            }
            for (NotificationHandler listener : mNotificationHandlers) {
                listener.onNotificationsInitialized();
            }
        });
        onSilentStatusBarIconsVisibilityChanged(
                mNotificationManager.shouldHideSilentStatusBarIcons());
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn,
            final RankingMap rankingMap) {
        if (DEBUG) Log.d(TAG, "onNotificationPosted: " + sbn);
        if (sbn != null && !onPluginNotificationPosted(sbn, rankingMap)) {
            mMainHandler.post(() -> {
                processForRemoteInput(sbn.getNotification(), mContext);

                for (NotificationHandler handler : mNotificationHandlers) {
                    handler.onNotificationPosted(sbn, rankingMap);
                }
            });
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            int reason) {
        if (DEBUG) Log.d(TAG, "onNotificationRemoved: " + sbn + " reason: " + reason);
        if (sbn != null && !onPluginNotificationRemoved(sbn, rankingMap)) {
            mMainHandler.post(() -> {
                for (NotificationHandler handler : mNotificationHandlers) {
                    handler.onNotificationRemoved(sbn, rankingMap, reason);
                }
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
                for (NotificationHandler handler : mNotificationHandlers) {
                    handler.onNotificationRankingUpdate(r);
                }
            });
        }
    }

    @Override
    public void onNotificationChannelModified(
            String pkgName, UserHandle user, NotificationChannel channel, int modificationType) {
        if (DEBUG) Log.d(TAG, "onNotificationChannelModified");
        if (!onPluginNotificationChannelModified(pkgName, user, channel, modificationType)) {
            mMainHandler.post(() -> {
                for (NotificationHandler handler : mNotificationHandlers) {
                    handler.onNotificationChannelModified(pkgName, user, channel, modificationType);
                }
            });
        }
    }

    @Override
    public void onSilentStatusBarIconsVisibilityChanged(boolean hideSilentStatusIcons) {
        for (NotificationSettingsListener listener : mSettingsListeners) {
            listener.onStatusBarIconsBehaviorChanged(hideSilentStatusIcons);
        }
    }

    public final void unsnoozeNotification(@NonNull String key) {
        if (!isBound()) return;
        try {
            getNotificationInterface().unsnoozeNotificationFromSystemListener(mWrapper, key);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
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
                    false,
                    false,
                    null,
                    0,
                    false
            );
        }
        return ranking;
    }

    public interface NotificationSettingsListener {

        default void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) { }
    }

    /** Interface for listening to add/remove events that we receive from NotificationManager. */
    public interface NotificationHandler {
        void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap);
        void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap);
        void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason);
        void onNotificationRankingUpdate(RankingMap rankingMap);

        /** Called after a notification channel is modified. */
        default void onNotificationChannelModified(
                String pkgName,
                UserHandle user,
                NotificationChannel channel,
                int modificationType) {
        }

        /**
         * Called after the listener has connected to NoMan and posted any current notifications.
         */
        void onNotificationsInitialized();
    }
}
