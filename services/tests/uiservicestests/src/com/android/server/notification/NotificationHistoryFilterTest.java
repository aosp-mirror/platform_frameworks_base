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
package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.graphics.drawable.Icon;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationHistoryFilterTest extends UiServiceTestCase {

    private HistoricalNotification getHistoricalNotification(int index) {
        return getHistoricalNotification("package" + index, "channelId" + index, index);
    }
    private HistoricalNotification getHistoricalNotification(String pkg, int index) {
        return getHistoricalNotification(pkg, "channelId" + index, index);
    }

    private HistoricalNotification getHistoricalNotification(String packageName, String channelId,
            int index) {
        String expectedChannelName = "channelName" + index;
        int expectedUid = 1123456 + index;
        int expectedUserId = 11 + index;
        long expectedPostTime = 987654321 + index;
        String expectedTitle = "title" + index;
        String expectedText = "text" + index;
        Icon expectedIcon = Icon.createWithResource(InstrumentationRegistry.getContext(),
                index);

        return new HistoricalNotification.Builder()
                .setPackage(packageName)
                .setChannelName(expectedChannelName)
                .setChannelId(channelId)
                .setUid(expectedUid)
                .setUserId(expectedUserId)
                .setPostedTimeMs(expectedPostTime)
                .setTitle(expectedTitle)
                .setText(expectedText)
                .setIcon(expectedIcon)
                .build();
    }

    @Test
    public void testBuilder() {
        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setChannel("pkg", "channel")
                .setMaxNotifications(3)
                .build();

        assertThat(filter.getPackage()).isEqualTo("pkg");
        assertThat(filter.getChannel()).isEqualTo("channel");
        assertThat(filter.getMaxNotifications()).isEqualTo(3);
    }

    @Test
    public void testMatchesCountFilter() {
        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setMaxNotifications(3)
                .build();

        NotificationHistory history = new NotificationHistory();
        assertThat(filter.matchesCountFilter(history)).isTrue();
        history.addNotificationToWrite(getHistoricalNotification(1));
        assertThat(filter.matchesCountFilter(history)).isTrue();
        history.addNotificationToWrite(getHistoricalNotification(2));
        assertThat(filter.matchesCountFilter(history)).isTrue();
        history.addNotificationToWrite(getHistoricalNotification(3));
        assertThat(filter.matchesCountFilter(history)).isFalse();
    }

    @Test
    public void testMatchesCountFilter_noCountFilter() {
        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .build();

        NotificationHistory history = new NotificationHistory();
        assertThat(filter.matchesCountFilter(history)).isTrue();
        history.addNotificationToWrite(getHistoricalNotification(1));
        assertThat(filter.matchesCountFilter(history)).isTrue();
    }

    @Test
    public void testMatchesPackageAndChannelFilter_pkgOnly() {
        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setPackage("pkg")
                .build();

        HistoricalNotification hnMatches = getHistoricalNotification("pkg", 1);
        assertThat(filter.matchesPackageAndChannelFilter(hnMatches)).isTrue();
        HistoricalNotification hnMatches2 = getHistoricalNotification("pkg", 2);
        assertThat(filter.matchesPackageAndChannelFilter(hnMatches2)).isTrue();

        HistoricalNotification hnNoMatch = getHistoricalNotification("pkg2", 2);
        assertThat(filter.matchesPackageAndChannelFilter(hnNoMatch)).isFalse();
    }

    @Test
    public void testMatchesPackageAndChannelFilter_channelAlso() {
        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setChannel("pkg", "channel")
                .build();

        HistoricalNotification hn1 = getHistoricalNotification("pkg", 1);
        assertThat(filter.matchesPackageAndChannelFilter(hn1)).isFalse();

        HistoricalNotification hn2 = getHistoricalNotification("pkg", "channel", 1);
        assertThat(filter.matchesPackageAndChannelFilter(hn2)).isTrue();

        HistoricalNotification hn3 = getHistoricalNotification("pkg2", "channel", 1);
        assertThat(filter.matchesPackageAndChannelFilter(hn3)).isFalse();
    }

    @Test
    public void testIsFiltering() {
        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .build();
        assertThat(filter.isFiltering()).isFalse();

        filter = new NotificationHistoryFilter.Builder()
                .setPackage("pkg")
                .build();
        assertThat(filter.isFiltering()).isTrue();

        filter = new NotificationHistoryFilter.Builder()
                .setChannel("pkg", "channel")
                .build();
        assertThat(filter.isFiltering()).isTrue();

        filter = new NotificationHistoryFilter.Builder()
                .setMaxNotifications(5)
                .build();
        assertThat(filter.isFiltering()).isTrue();
    }
}
