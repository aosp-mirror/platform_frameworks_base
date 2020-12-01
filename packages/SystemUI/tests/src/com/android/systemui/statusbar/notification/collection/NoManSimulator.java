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

package com.android.systemui.statusbar.notification.collection;

import static org.junit.Assert.assertNotNull;

import android.app.NotificationChannel;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import com.android.systemui.statusbar.NotificationListener.NotificationHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simulates a NotificationManager
 *
 * You can post and retract notifications, each with an accompanying Ranking. The simulator will
 * keep its RankingMap up to date and call appropriate event listeners.
 */
public class NoManSimulator {
    private final List<NotificationHandler> mListeners = new ArrayList<>();
    private final Map<String, Ranking> mRankings = new ArrayMap<>();

    public NoManSimulator() {
    }

    public void addListener(NotificationHandler listener) {
        mListeners.add(listener);
    }

    public NotifEvent postNotif(NotificationEntryBuilder builder) {
        final NotificationEntry entry = builder.build();
        mRankings.put(entry.getKey(), entry.getRanking());
        final RankingMap rankingMap = buildRankingMap();
        for (NotificationHandler listener : mListeners) {
            listener.onNotificationPosted(entry.getSbn(), rankingMap);
        }
        return new NotifEvent(entry.getSbn(), entry.getRanking(), rankingMap);
    }

    public NotifEvent retractNotif(StatusBarNotification sbn, int reason) {
        assertNotNull(mRankings.remove(sbn.getKey()));
        final RankingMap rankingMap = buildRankingMap();
        for (NotificationHandler listener : mListeners) {
            listener.onNotificationRemoved(sbn, rankingMap, reason);
        }
        return new NotifEvent(sbn, null, rankingMap);
    }

    public void issueRankingUpdate() {
        final RankingMap rankingMap = buildRankingMap();
        for (NotificationHandler listener : mListeners) {
            listener.onNotificationRankingUpdate(rankingMap);
        }
    }

    public void issueChannelModification(
            String pkg, UserHandle user, NotificationChannel channel, int modificationType) {
        for (NotificationHandler listener : mListeners) {
            listener.onNotificationChannelModified(pkg, user, channel, modificationType);
        }
    }

    public void setRanking(String key, Ranking ranking) {
        mRankings.put(key, ranking);
    }

    private RankingMap buildRankingMap() {
        return new RankingMap(mRankings.values().toArray(new Ranking[0]));
    }

    public static class NotifEvent {
        public final String key;
        public final StatusBarNotification sbn;
        public final Ranking ranking;
        public final RankingMap rankingMap;

        private NotifEvent(
                StatusBarNotification sbn,
                Ranking ranking,
                RankingMap rankingMap) {
            this.key = sbn.getKey();
            this.sbn = sbn;
            this.ranking = ranking;
            this.rankingMap = rankingMap;
        }
    }
}
