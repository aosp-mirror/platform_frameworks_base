/**
 * Copyright (c) 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.notification;

import static android.service.notification.NotificationStats.DISMISSAL_PEEK;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.os.Parcel;
import android.service.notification.NotificationStats;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationStatsTest extends UiServiceTestCase {

    @Test
    public void testConstructor() {
        NotificationStats stats = new NotificationStats();

        assertFalse(stats.hasSeen());
        assertFalse(stats.hasDirectReplied());
        assertFalse(stats.hasExpanded());
        assertFalse(stats.hasInteracted());
        assertFalse(stats.hasViewedSettings());
        assertFalse(stats.hasSnoozed());
        assertEquals(NotificationStats.DISMISSAL_NOT_DISMISSED, stats.getDismissalSurface());
    }

    @Test
    public void testSeen() {
        NotificationStats stats = new NotificationStats();
        stats.setSeen();
        assertTrue(stats.hasSeen());
        assertFalse(stats.hasInteracted());
    }

    @Test
    public void testDirectReplied() {
        NotificationStats stats = new NotificationStats();
        stats.setDirectReplied();
        assertTrue(stats.hasDirectReplied());
        assertTrue(stats.hasInteracted());
    }

    @Test
    public void testExpanded() {
        NotificationStats stats = new NotificationStats();
        stats.setExpanded();
        assertTrue(stats.hasExpanded());
        assertTrue(stats.hasInteracted());
    }

    @Test
    public void testSnoozed() {
        NotificationStats stats = new NotificationStats();
        stats.setSnoozed();
        assertTrue(stats.hasSnoozed());
        assertTrue(stats.hasInteracted());
    }

    @Test
    public void testViewedSettings() {
        NotificationStats stats = new NotificationStats();
        stats.setViewedSettings();
        assertTrue(stats.hasViewedSettings());
        assertTrue(stats.hasInteracted());
    }

    @Test
    public void testDismissalSurface() {
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(DISMISSAL_PEEK);
        assertEquals(DISMISSAL_PEEK, stats.getDismissalSurface());
        assertFalse(stats.hasInteracted());
    }

    @Test
    public void testWriteToParcel() {
        NotificationStats stats = new NotificationStats();
        stats.setViewedSettings();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_AOD);
        Parcel parcel = Parcel.obtain();
        stats.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationStats stats1 = NotificationStats.CREATOR.createFromParcel(parcel);
        assertEquals(stats, stats1);
    }
}
