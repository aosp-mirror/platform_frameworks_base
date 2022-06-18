/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.annotation.NonNull;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/***
 * {@link DreamOverlayNotificationCountProvider} provides the current notification count to
 * registered callbacks. Ongoing notifications are not included in the count.
 */
public class DreamOverlayNotificationCountProvider
        implements CallbackController<DreamOverlayNotificationCountProvider.Callback> {
    private final Set<String> mNotificationKeys = new HashSet<>();
    private final List<Callback> mCallbacks = new ArrayList<>();

    private final NotificationHandler mNotificationHandler = new NotificationHandler() {
        @Override
        public void onNotificationPosted(
                StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
            if (sbn.isOngoing()) {
                // Don't count ongoing notifications.
                return;
            }
            mNotificationKeys.add(sbn.getKey());
            reportNotificationCountChanged();
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
            mNotificationKeys.remove(sbn.getKey());
            reportNotificationCountChanged();
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap,
                int reason) {
            mNotificationKeys.remove(sbn.getKey());
            reportNotificationCountChanged();
        }

        @Override
        public void onNotificationRankingUpdate(NotificationListenerService.RankingMap rankingMap) {
        }

        @Override
        public void onNotificationsInitialized() {
        }
    };

    public DreamOverlayNotificationCountProvider(
            NotificationListener notificationListener,
            @Background Executor bgExecutor) {
        notificationListener.addNotificationHandler(mNotificationHandler);

        bgExecutor.execute(() -> {
                    Arrays.stream(notificationListener.getActiveNotifications())
                            .forEach(sbn -> mNotificationKeys.add(sbn.getKey()));
                    reportNotificationCountChanged();
                }
        );
    }

    @Override
    public void addCallback(@NonNull Callback callback) {
        if (!mCallbacks.contains(callback)) {
            mCallbacks.add(callback);
            callback.onNotificationCountChanged(mNotificationKeys.size());
        }
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        mCallbacks.remove(callback);
    }

    private void reportNotificationCountChanged() {
        final int notificationCount = mNotificationKeys.size();
        mCallbacks.forEach(callback -> callback.onNotificationCountChanged(notificationCount));
    }

    /**
     * A callback to be registered with {@link DreamOverlayNotificationCountProvider} to receive
     * changes to the current notification count.
     */
    public interface Callback {
        /**
         * Called when the notification count has changed.
         * @param count The current notification count.
         */
        void onNotificationCountChanged(int count);
    }
}
