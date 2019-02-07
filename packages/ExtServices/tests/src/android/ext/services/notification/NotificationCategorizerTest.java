/**
 * Copyright (C) 2018 The Android Open Source Project
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

package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.os.Process.FIRST_APPLICATION_UID;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.testing.TestableContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class NotificationCategorizerTest {
    @Mock
    private NotificationEntry mEntry;
    @Mock
    private StatusBarNotification mSbn;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mEntry.getSbn()).thenReturn(mSbn);
        when(mSbn.getUid()).thenReturn(Process.myUid());
        when(mSbn.getPackageName()).thenReturn(mContext.getPackageName());
    }

    @Test
    public void testPeopleCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.involvesPeople()).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_PEOPLE, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_PEOPLE));
    }

    @Test
    public void testMin() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_MIN));
        when(mEntry.involvesPeople()).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_MIN, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_MIN));
    }

    @Test
    public void testHigh() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_HIGH));

        assertEquals(NotificationCategorizer.CATEGORY_HIGH, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_HIGH));
    }

    @Test
    public void testOngoingCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.isOngoing()).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_ONGOING, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_ONGOING));

        when(mEntry.isOngoing()).thenReturn(false);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE));
    }

    @Test
    public void testAlarmCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.isCategory(Notification.CATEGORY_ALARM)).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_ALARM, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_ALARM));

        when(mEntry.isCategory(Notification.CATEGORY_ALARM)).thenReturn(false);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE));
    }

    @Test
    public void testCallCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.isCategory(Notification.CATEGORY_CALL)).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_CALL, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_CALL));

        when(mEntry.isCategory(Notification.CATEGORY_CALL)).thenReturn(false);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE));
    }

    @Test
    public void testReminderCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.isCategory(Notification.CATEGORY_REMINDER)).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_REMINDER, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_REMINDER));

        when(mEntry.isCategory(Notification.CATEGORY_REMINDER)).thenReturn(false);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE));
    }

    @Test
    public void testEventCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.isCategory(Notification.CATEGORY_EVENT)).thenReturn(true);

        assertEquals(NotificationCategorizer.CATEGORY_EVENT, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_EVENT));

        when(mEntry.isCategory(Notification.CATEGORY_EVENT)).thenReturn(false);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
    }

    @Test
    public void testSystemCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_DEFAULT));
        when(mEntry.getImportance()).thenReturn(IMPORTANCE_DEFAULT);
        when(mSbn.getUid()).thenReturn(FIRST_APPLICATION_UID - 1);

        assertEquals(NotificationCategorizer.CATEGORY_SYSTEM, nc.getCategory(mEntry));
        assertFalse(nc.shouldSilence(NotificationCategorizer.CATEGORY_SYSTEM));

        when(mSbn.getUid()).thenReturn(FIRST_APPLICATION_UID);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
    }

    @Test
    public void testSystemLowCategory() {
        NotificationCategorizer nc = new NotificationCategorizer();

        when(mEntry.getChannel()).thenReturn(new NotificationChannel("", "", IMPORTANCE_LOW));
        when(mEntry.getImportance()).thenReturn(IMPORTANCE_LOW);
        when(mSbn.getUid()).thenReturn(FIRST_APPLICATION_UID - 1);

        assertEquals(NotificationCategorizer.CATEGORY_SYSTEM_LOW, nc.getCategory(mEntry));
        assertTrue(nc.shouldSilence(NotificationCategorizer.CATEGORY_SYSTEM_LOW));

        when(mSbn.getUid()).thenReturn(FIRST_APPLICATION_UID);
        assertEquals(NotificationCategorizer.CATEGORY_EVERYTHING_ELSE, nc.getCategory(mEntry));
    }
}
