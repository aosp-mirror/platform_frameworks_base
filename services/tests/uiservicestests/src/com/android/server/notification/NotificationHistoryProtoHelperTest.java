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
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationHistoryProtoHelperTest extends UiServiceTestCase {

    private HistoricalNotification getHistoricalNotification(int index) {
        return getHistoricalNotification("package" + index, index);
    }

    private HistoricalNotification getHistoricalNotification(String packageName, int index) {
        String expectedChannelName = "channelName" + index;
        String expectedChannelId = "channelId" + index;
        int expectedUid = 1123456 + index;
        int expectedUserId = 11 + index;
        long expectedPostTime = 987654321 + index;
        String expectedTitle = "title" + index;
        String expectedText = "text" + index;
        Icon expectedIcon = Icon.createWithResource(InstrumentationRegistry.getContext(),
                index);
        String conversationId = null;
        if (index % 2 == 0) {
            conversationId = "convo" + index;
        }

        return new HistoricalNotification.Builder()
                .setPackage(packageName)
                .setChannelName(expectedChannelName)
                .setChannelId(expectedChannelId)
                .setUid(expectedUid)
                .setUserId(expectedUserId)
                .setPostedTimeMs(expectedPostTime)
                .setTitle(expectedTitle)
                .setText(expectedText)
                .setIcon(expectedIcon)
                .setConversationId(conversationId)
                .build();
    }

    @Test
    public void testReadWriteNotifications() throws Exception {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        // loops backwards just to maintain the post time newest -> oldest expectation
        for (int i = 10; i >= 1; i--) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedEntries.add(n);
            history.addNotificationToWrite(n);
        }
        history.poolStringsFromNotifications();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NotificationHistoryProtoHelper.write(baos, history, 1);

        NotificationHistory actualHistory = new NotificationHistory();
        NotificationHistoryProtoHelper.read(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                actualHistory,
                new NotificationHistoryFilter.Builder().build());

        assertThat(actualHistory.getHistoryCount()).isEqualTo(history.getHistoryCount());
        assertThat(actualHistory.getNotificationsToWrite())
                .containsExactlyElementsIn(expectedEntries);
    }

    @Test
    public void testReadWriteNotifications_stringFieldsPersistedEvenIfNoPool() throws Exception {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        // loops backwards just to maintain the post time newest -> oldest expectation
        for (int i = 10; i >= 1; i--) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedEntries.add(n);
            history.addNotificationToWrite(n);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NotificationHistoryProtoHelper.write(baos, history, 1);

        NotificationHistory actualHistory = new NotificationHistory();
        NotificationHistoryProtoHelper.read(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                actualHistory,
                new NotificationHistoryFilter.Builder().build());

        assertThat(actualHistory.getHistoryCount()).isEqualTo(history.getHistoryCount());
        assertThat(actualHistory.getNotificationsToWrite())
                .containsExactlyElementsIn(expectedEntries);
    }

    @Test
    public void testReadNotificationsWithPkgFilter() throws Exception {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        Set<String> expectedStrings = new HashSet<>();
        // loops backwards just to maintain the post time newest -> oldest expectation
        for (int i = 10; i >= 1; i--) {
            HistoricalNotification n =
                    getHistoricalNotification((i % 2 == 0) ? "pkgEven" : "pkgOdd", i);

            if (i % 2 == 0) {
                expectedStrings.add(n.getPackage());
                expectedStrings.add(n.getChannelName());
                expectedStrings.add(n.getChannelId());
                if (!TextUtils.isEmpty(n.getConversationId())) {
                    expectedStrings.add(n.getConversationId());
                }
                expectedEntries.add(n);
            }
            history.addNotificationToWrite(n);
        }
        history.poolStringsFromNotifications();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NotificationHistoryProtoHelper.write(baos, history, 1);

        NotificationHistory actualHistory = new NotificationHistory();

        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setPackage("pkgEven")
                .build();
        NotificationHistoryProtoHelper.read(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                actualHistory,
                filter);

        assertThat(actualHistory.getNotificationsToWrite())
                .containsExactlyElementsIn(expectedEntries);
        assertThat(Arrays.asList(actualHistory.getPooledStringsToWrite()))
                .containsExactlyElementsIn(expectedStrings);
    }

    @Test
    public void testReadNotificationsWithNumberFilter() throws Exception {
        int maxCount = 3;
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        Set<String> expectedStrings = new HashSet<>();
        for (int i = 1; i < 10; i++) {
            HistoricalNotification n = getHistoricalNotification(i);

            if (i <= maxCount) {
                expectedStrings.add(n.getPackage());
                expectedStrings.add(n.getChannelName());
                expectedStrings.add(n.getChannelId());
                if (!TextUtils.isEmpty(n.getConversationId())) {
                    expectedStrings.add(n.getConversationId());
                }
                expectedEntries.add(n);
            }
            history.addNotificationToWrite(n);
        }
        history.poolStringsFromNotifications();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NotificationHistoryProtoHelper.write(baos, history, 1);

        NotificationHistory actualHistory = new NotificationHistory();

        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setMaxNotifications(maxCount)
                .build();
        NotificationHistoryProtoHelper.read(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                actualHistory,
                filter);

        assertThat(actualHistory.getNotificationsToWrite())
                .containsExactlyElementsIn(expectedEntries);
        assertThat(Arrays.asList(actualHistory.getPooledStringsToWrite()))
                .containsExactlyElementsIn(expectedStrings);
    }

    @Test
    public void testReadNotificationsWithNumberFilter_preExistingNotifs() throws Exception {
        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        Set<String> expectedStrings = new HashSet<>();
        int maxCount = 3;

        NotificationHistory history = new NotificationHistory();
        HistoricalNotification old1 = getHistoricalNotification(40);
        history.addNotificationToWrite(old1);
        expectedEntries.add(old1);

        HistoricalNotification old2 = getHistoricalNotification(50);
        history.addNotificationToWrite(old2);
        expectedEntries.add(old2);
        history.poolStringsFromNotifications();
        expectedStrings.addAll(Arrays.asList(history.getPooledStringsToWrite()));

        for (int i = 1; i < 10; i++) {
            HistoricalNotification n = getHistoricalNotification(i);

            if (i <= (maxCount - 2)) {
                expectedStrings.add(n.getPackage());
                expectedStrings.add(n.getChannelName());
                expectedStrings.add(n.getChannelId());
                if (n.getConversationId() != null) {
                    expectedStrings.add(n.getConversationId());
                }
                expectedEntries.add(n);
            }
            history.addNotificationToWrite(n);
        }
        history.poolStringsFromNotifications();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NotificationHistoryProtoHelper.write(baos, history, 1);

        NotificationHistory actualHistory = new NotificationHistory();

        NotificationHistoryFilter filter = new NotificationHistoryFilter.Builder()
                .setMaxNotifications(maxCount)
                .build();
        NotificationHistoryProtoHelper.read(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                actualHistory,
                filter);

        assertThat(actualHistory.getNotificationsToWrite())
                .containsExactlyElementsIn(expectedEntries);
        assertThat(Arrays.asList(actualHistory.getPooledStringsToWrite()))
                .containsExactlyElementsIn(expectedStrings);
    }

    @Test
    public void testReadMergeIntoExistingHistory() throws Exception {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        Set<String> expectedStrings = new HashSet<>();
        for (int i = 1; i < 10; i++) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedEntries.add(n);
            expectedStrings.add(n.getPackage());
            expectedStrings.add(n.getChannelName());
            expectedStrings.add(n.getChannelId());
            if (n.getConversationId() != null) {
                expectedStrings.add(n.getConversationId());
            }
            history.addNotificationToWrite(n);
        }
        history.poolStringsFromNotifications();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NotificationHistoryProtoHelper.write(baos, history, 1);

        // set up pre-existing notification history, as though read from a different file
        NotificationHistory actualHistory = new NotificationHistory();
        for (int i = 10; i < 20; i++) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedEntries.add(n);
            expectedStrings.add(n.getPackage());
            expectedStrings.add(n.getChannelName());
            expectedStrings.add(n.getChannelId());
            if (n.getConversationId() != null) {
                expectedStrings.add(n.getConversationId());
            }
            actualHistory.addNotificationToWrite(n);
        }
        actualHistory.poolStringsFromNotifications();

        NotificationHistoryProtoHelper.read(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())),
                actualHistory,
                new NotificationHistoryFilter.Builder().build());

        // Make sure history contains the original and new entries
        assertThat(actualHistory.getNotificationsToWrite())
                .containsExactlyElementsIn(expectedEntries);
        assertThat(Arrays.asList(actualHistory.getPooledStringsToWrite()))
                .containsExactlyElementsIn(expectedStrings);
    }
}
