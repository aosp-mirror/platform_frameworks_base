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

package com.android.systemui.statusbar.notification.collection.provider;

import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.Person;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class IsHighPriorityProviderTest extends SysuiTestCase {
    private IsHighPriorityProvider mIsHighPriorityProvider;

    @Before
    public void setup() {
        mIsHighPriorityProvider = new IsHighPriorityProvider();
    }

    @Test
    public void testCache() {
        // GIVEN a notification with high importance
        final NotificationEntry entryHigh = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .build();

        // GIVEN notification with min importance
        final NotificationEntry entryMin = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_MIN)
                .build();

        // WHEN we get the value for the high priority entry
        assertTrue(mIsHighPriorityProvider.get(entryHigh));

        // THEN the value is cached, so even when passed an entryMin, we still get high priority
        assertTrue(mIsHighPriorityProvider.get(entryMin));

        // UNTIL the provider is invalidated
        mIsHighPriorityProvider.invalidate();

        // THEN the priority is recalculated
        assertFalse(mIsHighPriorityProvider.get(entryMin));
    }

    @Test
    public void highImportance() {
        // GIVEN notification has high importance
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .build();

        // THEN it has high priority
        assertTrue(mIsHighPriorityProvider.get(entry));
    }

    @Test
    public void peopleNotification() {
        // GIVEN notification is low importance but has a person associated with it
        final Notification notification = new Notification.Builder(mContext, "test")
                .addPerson(
                        new Person.Builder()
                                .setName("name")
                                .setKey("abc")
                                .setUri("uri")
                                .setBot(true)
                                .build())
                .build();

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_LOW)
                .build();

        // THEN it has high priority
        assertTrue(mIsHighPriorityProvider.get(entry));
    }

    @Test
    public void messagingStyle() {
        // GIVEN notification is low importance but has messaging style
        final Notification notification = new Notification.Builder(mContext, "test")
                .setStyle(new Notification.MessagingStyle(""))
                .build();

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .build();

        // THEN it has high priority
        assertTrue(mIsHighPriorityProvider.get(entry));
    }

    @Test
    public void lowImportanceForeground() {
        // GIVEN notification is low importance and is associated with a foreground service
        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_LOW)
                .build();

        // THEN it has high priority
        assertTrue(mIsHighPriorityProvider.get(entry));
    }

    @Test
    public void minImportanceForeground() {
        // GIVEN notification is low importance and is associated with a foreground service
        final Notification notification = mock(Notification.class);
        when(notification.isForegroundService()).thenReturn(true);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setImportance(IMPORTANCE_MIN)
                .build();

        // THEN it does NOT have high priority
        assertFalse(mIsHighPriorityProvider.get(entry));
    }

    @Test
    public void userChangeTrumpsHighPriorityCharacteristics() {
        // GIVEN notification has high priority characteristics but the user changed the importance
        // to less than IMPORTANCE_DEFAULT (ie: IMPORTANCE_LOW or IMPORTANCE_MIN)
        final Notification notification = new Notification.Builder(mContext, "test")
                .addPerson(
                        new Person.Builder()
                                .setName("name")
                                .setKey("abc")
                                .setUri("uri")
                                .setBot(true)
                                .build())
                .setStyle(new Notification.MessagingStyle(""))
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();

        final NotificationChannel channel = new NotificationChannel("a", "a",
                IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);

        final NotificationEntry entry = new NotificationEntryBuilder()
                .setNotification(notification)
                .setChannel(channel)
                .build();

        // THEN it does NOT have high priority
        assertFalse(mIsHighPriorityProvider.get(entry));
    }
}
