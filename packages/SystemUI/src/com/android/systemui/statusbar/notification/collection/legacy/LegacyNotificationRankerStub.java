/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.legacy;

import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Stub implementation that we use until we get passed the "real" one in the form of
 * {@link com.android.systemui.statusbar.notification.collection.NotificationRankingManager}
 */
public class LegacyNotificationRankerStub implements LegacyNotificationRanker {
    private RankingMap mRankingMap = new RankingMap(new Ranking[] {});

    @NonNull
    @Override
    public List<NotificationEntry> updateRanking(
            @Nullable RankingMap newRankingMap,
            @NonNull Collection<NotificationEntry> entries,
            @NonNull String reason) {
        if (newRankingMap != null) {
            mRankingMap = newRankingMap;
        }
        List<NotificationEntry> ranked = new ArrayList<>(entries);
        ranked.sort(mEntryComparator);
        return ranked;
    }

    @Nullable
    @Override
    public RankingMap getRankingMap() {
        return mRankingMap;
    }

    private final Comparator<NotificationEntry> mEntryComparator = Comparator.comparingLong(
            o -> o.getSbn().getNotification().when);

    @Override
    public boolean isNotificationForCurrentProfiles(@NonNull NotificationEntry entry) {
        return true;
    }
}
