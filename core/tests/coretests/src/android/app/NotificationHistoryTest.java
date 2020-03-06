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

package android.app;

import static com.google.common.truth.Truth.assertThat;

import android.app.NotificationHistory.HistoricalNotification;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.util.Slog;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NotificationHistoryTest {

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
    public void testHistoricalNotificationBuilder() {
        String expectedPackage = "package";
        String expectedChannelName = "channelName";
        String expectedChannelId = "channelId";
        int expectedUid = 1123456;
        int expectedUserId = 11;
        long expectedPostTime = 987654321;
        String expectedTitle = "title";
        String expectedText = "text";
        Icon expectedIcon = Icon.createWithResource(InstrumentationRegistry.getContext(),
                android.R.drawable.btn_star);
        String expectedConversationId = "convo";

        HistoricalNotification n = new HistoricalNotification.Builder()
                .setPackage(expectedPackage)
                .setChannelName(expectedChannelName)
                .setChannelId(expectedChannelId)
                .setUid(expectedUid)
                .setUserId(expectedUserId)
                .setPostedTimeMs(expectedPostTime)
                .setTitle(expectedTitle)
                .setText(expectedText)
                .setIcon(expectedIcon)
                .setConversationId(expectedConversationId)
                .build();

        assertThat(n.getPackage()).isEqualTo(expectedPackage);
        assertThat(n.getChannelName()).isEqualTo(expectedChannelName);
        assertThat(n.getChannelId()).isEqualTo(expectedChannelId);
        assertThat(n.getUid()).isEqualTo(expectedUid);
        assertThat(n.getUserId()).isEqualTo(expectedUserId);
        assertThat(n.getPostedTimeMs()).isEqualTo(expectedPostTime);
        assertThat(n.getTitle()).isEqualTo(expectedTitle);
        assertThat(n.getText()).isEqualTo(expectedText);
        assertThat(expectedIcon.sameAs(n.getIcon())).isTrue();
        assertThat(n.getConversationId()).isEqualTo(expectedConversationId);
    }

    @Test
    public void testAddNotificationToWrite() {
        NotificationHistory history = new NotificationHistory();
        HistoricalNotification n = getHistoricalNotification(0);
        HistoricalNotification n2 = getHistoricalNotification(1);

        history.addNotificationToWrite(n2);
        history.addNotificationToWrite(n);

        assertThat(history.getNotificationsToWrite().size()).isEqualTo(2);
        assertThat(history.getNotificationsToWrite().get(0)).isSameAs(n2);
        assertThat(history.getNotificationsToWrite().get(1)).isSameAs(n);
        assertThat(history.getHistoryCount()).isEqualTo(2);
    }

    @Test
    public void testAddNotificationsToWrite() {
        NotificationHistory history = new NotificationHistory();
        HistoricalNotification n = getHistoricalNotification(3);
        HistoricalNotification n2 = getHistoricalNotification(1);
        HistoricalNotification n5 = getHistoricalNotification(0);
        history.addNotificationToWrite(n2);
        history.addNotificationToWrite(n);
        history.addNotificationToWrite(n5);

        NotificationHistory secondHistory = new NotificationHistory();
        HistoricalNotification n3 = getHistoricalNotification(4);
        HistoricalNotification n4 = getHistoricalNotification(2);
        secondHistory.addNotificationToWrite(n4);
        secondHistory.addNotificationToWrite(n3);

        history.addNotificationsToWrite(secondHistory);

        assertThat(history.getNotificationsToWrite().size()).isEqualTo(5);
        assertThat(history.getNotificationsToWrite().get(0)).isSameAs(n3);
        assertThat(history.getNotificationsToWrite().get(1)).isSameAs(n);
        assertThat(history.getNotificationsToWrite().get(2)).isSameAs(n4);
        assertThat(history.getNotificationsToWrite().get(3)).isSameAs(n2);
        assertThat(history.getNotificationsToWrite().get(4)).isSameAs(n5);
        assertThat(history.getHistoryCount()).isEqualTo(5);

        assertThat(history.getPooledStringsToWrite()).asList().contains(n2.getChannelName());
        assertThat(history.getPooledStringsToWrite()).asList().contains(n4.getPackage());
    }

    @Test
    public void testPoolStringsFromNotifications() {
        NotificationHistory history = new NotificationHistory();

        List<String> expectedStrings = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedStrings.add(n.getPackage());
            expectedStrings.add(n.getChannelName());
            expectedStrings.add(n.getChannelId());
            if (n.getConversationId() != null) {
                expectedStrings.add(n.getConversationId());
            }
            history.addNotificationToWrite(n);
        }

        history.poolStringsFromNotifications();

        assertThat(history.getPooledStringsToWrite().length).isEqualTo(expectedStrings.size());
        String previous = null;
        for (String actual : history.getPooledStringsToWrite()) {
            assertThat(expectedStrings).contains(actual);

            if (previous != null) {
                assertThat(actual).isGreaterThan(previous);
            }
            previous = actual;
        }
    }

    @Test
    public void testAddPooledStrings() {
        NotificationHistory history = new NotificationHistory();

        List<String> expectedStrings = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedStrings.add(n.getPackage());
            expectedStrings.add(n.getChannelName());
            expectedStrings.add(n.getChannelId());
            if (n.getConversationId() != null) {
                expectedStrings.add(n.getConversationId());
            }
            history.addNotificationToWrite(n);
        }

        history.addPooledStrings(expectedStrings);

        String[] actualStrings = history.getPooledStringsToWrite();
        assertThat(actualStrings.length).isEqualTo(expectedStrings.size());
        String previous = null;
        for (String actual : actualStrings) {
            assertThat(expectedStrings).contains(actual);

            if (previous != null) {
                assertThat(actual).isGreaterThan(previous);
            }
            previous = actual;
        }
    }

    @Test
    public void testRemoveNotificationsFromWrite() {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> postRemoveExpectedEntries = new ArrayList<>();
        List<String> postRemoveExpectedStrings = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            HistoricalNotification n =
                    getHistoricalNotification((i % 2 == 0) ? "pkgEven" : "pkgOdd", i);

            if (i % 2 == 0) {
                postRemoveExpectedStrings.add(n.getPackage());
                postRemoveExpectedStrings.add(n.getChannelName());
                postRemoveExpectedStrings.add(n.getChannelId());
                if (n.getConversationId() != null) {
                    postRemoveExpectedStrings.add(n.getConversationId());
                }
                postRemoveExpectedEntries.add(n);
            }

            history.addNotificationToWrite(n);
        }

        history.poolStringsFromNotifications();

        assertThat(history.getNotificationsToWrite().size()).isEqualTo(10);
        // 2 package names and 10 * 2 unique channel names and ids and 5 conversation ids
        assertThat(history.getPooledStringsToWrite().length).isEqualTo(27);

        history.removeNotificationsFromWrite("pkgOdd");


        // 1 package names and 5 * 2 unique channel names and ids and 5 conversation ids
        assertThat(history.getPooledStringsToWrite().length).isEqualTo(16);
        assertThat(history.getNotificationsToWrite())
                .containsExactlyElementsIn(postRemoveExpectedEntries);
    }

    @Test
    public void testRemoveNotificationFromWrite() {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> postRemoveExpectedEntries = new ArrayList<>();
        List<String> postRemoveExpectedStrings = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            HistoricalNotification n = getHistoricalNotification("pkg", i);

            if (987654323 != n.getPostedTimeMs()) {
                postRemoveExpectedStrings.add(n.getPackage());
                postRemoveExpectedStrings.add(n.getChannelName());
                postRemoveExpectedStrings.add(n.getChannelId());
                if (n.getConversationId() != null) {
                    postRemoveExpectedStrings.add(n.getConversationId());
                }
                postRemoveExpectedEntries.add(n);
            }

            history.addNotificationToWrite(n);
        }

        history.poolStringsFromNotifications();

        assertThat(history.getNotificationsToWrite().size()).isEqualTo(10);
        // 1 package name and 20 unique channel names and ids and 5 conversation ids
        assertThat(history.getPooledStringsToWrite().length).isEqualTo(26);

        history.removeNotificationFromWrite("pkg", 987654323);


        // 1 package names and 9 * 2 unique channel names and ids and 4 conversation ids
        assertThat(history.getPooledStringsToWrite().length).isEqualTo(23);
        assertThat(history.getNotificationsToWrite())
                .containsExactlyElementsIn(postRemoveExpectedEntries);
    }

    @Test
    public void testRemoveConversationNotificationFromWrite() {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> postRemoveExpectedEntries = new ArrayList<>();
        List<String> postRemoveExpectedStrings = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            HistoricalNotification n = getHistoricalNotification("pkg", i);

            if (i != 2) {
                postRemoveExpectedStrings.add(n.getPackage());
                postRemoveExpectedStrings.add(n.getChannelName());
                postRemoveExpectedStrings.add(n.getChannelId());
                if (n.getConversationId() != null) {
                    postRemoveExpectedStrings.add(n.getConversationId());
                }
                postRemoveExpectedEntries.add(n);
            }

            history.addNotificationToWrite(n);
        }
        // add second notification with the same conversation id that will be removed
        history.addNotificationToWrite(getHistoricalNotification("pkg", 2));

        history.poolStringsFromNotifications();

        assertThat(history.getNotificationsToWrite().size()).isEqualTo(11);
        // 1 package name and 20 unique channel names and ids and 5 conversation ids
        assertThat(history.getPooledStringsToWrite().length).isEqualTo(26);

        history.removeConversationFromWrite("pkg", "convo2");

        // 1 package names and 9 * 2 unique channel names and ids and 4 conversation ids
        assertThat(history.getPooledStringsToWrite().length).isEqualTo(23);
        assertThat(history.getNotificationsToWrite())
                .containsExactlyElementsIn(postRemoveExpectedEntries);
    }

    @Test
    public void testParceling() {
        NotificationHistory history = new NotificationHistory();

        List<HistoricalNotification> expectedEntries = new ArrayList<>();
        for (int i = 10; i >= 1; i--) {
            HistoricalNotification n = getHistoricalNotification(i);
            expectedEntries.add(n);
            history.addNotificationToWrite(n);
        }
        history.poolStringsFromNotifications();

        Parcel parcel = Parcel.obtain();
        history.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationHistory parceledHistory = NotificationHistory.CREATOR.createFromParcel(parcel);

        assertThat(parceledHistory.getHistoryCount()).isEqualTo(expectedEntries.size());

        for (int i = 0; i < expectedEntries.size(); i++) {
            assertThat(parceledHistory.hasNextNotification()).isTrue();

            HistoricalNotification postParcelNotification = parceledHistory.getNextNotification();
            assertThat(postParcelNotification).isEqualTo(expectedEntries.get(i));
        }
        assertThat(parceledHistory.hasNextNotification()).isFalse();
        assertThat(parceledHistory.getNextNotification()).isNull();
    }
}
