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
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationListenerWithPlugins;

import java.util.ArrayList;

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
    private final NotificationRemoteInputManager mRemoteInputManager =
            Dependency.get(NotificationRemoteInputManager.class);
    private final NotificationEntryManager mEntryManager =
            Dependency.get(NotificationEntryManager.class);
    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);

    private final Context mContext;
    private final ArrayList<NotificationSettingsListener> mSettingsListeners = new ArrayList<>();
    @Nullable private NotifServiceListener mDownstreamListener;

    @Inject
    public NotificationListener(Context context) {
        mContext = context;
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
        Dependency.get(Dependency.MAIN_HANDLER).post(() -> {
            for (StatusBarNotification sbn : notifications) {
                if (mDownstreamListener != null) {
                    mDownstreamListener.onNotificationPosted(sbn, currentRanking);
                }
                mEntryManager.addNotification(sbn, currentRanking);
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
            Dependency.get(Dependency.MAIN_HANDLER).post(() -> {
                processForRemoteInput(sbn.getNotification(), mContext);

                if (mDownstreamListener != null) {
                    mDownstreamListener.onNotificationPosted(sbn, rankingMap);
                }

                String key = sbn.getKey();
                boolean isUpdate =
                        mEntryManager.getNotificationData().get(key) != null;
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
                        mEntryManager.getNotificationData()
                                .updateRanking(rankingMap);
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
            Dependency.get(Dependency.MAIN_HANDLER).post(() -> {
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
            Dependency.get(Dependency.MAIN_HANDLER).post(() -> {
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
