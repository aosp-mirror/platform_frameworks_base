/*
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

package com.android.systemui.statusbar.phone;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.VisualStabilityManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HeadsUpManagerPhoneTest extends SysuiTestCase {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;

    private HeadsUpManagerPhone mHeadsUpManager;

    private NotificationData.Entry mEntry;
    private StatusBarNotification mSbn;

    @Mock private NotificationGroupManager mGroupManager;
    @Mock private View mStatusBarWindowView;
    @Mock private StatusBar mBar;
    @Mock private ExpandableNotificationRow mRow;
    @Mock private VisualStabilityManager mVSManager;

    @Before
    public void setUp() {
        when(mVSManager.isReorderingAllowed()).thenReturn(true);

        mHeadsUpManager = new HeadsUpManagerPhone(
                mContext, mStatusBarWindowView, mGroupManager, mBar, mVSManager);

        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID,
             0, n.build(), new UserHandle(ActivityManager.getCurrentUser()), null, 0);

        mEntry = new NotificationData.Entry(mSbn);
        mEntry.row = mRow;
        mEntry.expandedIcon = mock(StatusBarIconView.class);
    }

    @Test
    public void testBasicOperations() {
        // Check the initial state.
        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());

        // Add a notification.
        mHeadsUpManager.showNotification(mEntry);

        assertEquals(mEntry, mHeadsUpManager.getEntry(mEntry.key));
        assertEquals(mEntry, mHeadsUpManager.getTopEntry());
        assertEquals(1, mHeadsUpManager.getAllEntries().count());
        assertTrue(mHeadsUpManager.hasHeadsUpNotifications());

        // Update the notification.
        mHeadsUpManager.updateNotification(mEntry, false);

        assertEquals(mEntry, mHeadsUpManager.getEntry(mEntry.key));
        assertEquals(mEntry, mHeadsUpManager.getTopEntry());
        assertEquals(1, mHeadsUpManager.getAllEntries().count());
        assertTrue(mHeadsUpManager.hasHeadsUpNotifications());

        // Try to remove but defer, since the notification is currenlt visible on display.
        mHeadsUpManager.removeNotification(mEntry.key, false /* ignoreEarliestRemovalTime */);

        assertEquals(mEntry, mHeadsUpManager.getEntry(mEntry.key));
        assertEquals(mEntry, mHeadsUpManager.getTopEntry());
        assertEquals(1, mHeadsUpManager.getAllEntries().count());
        assertTrue(mHeadsUpManager.hasHeadsUpNotifications());

        // Remove forcibly with ignoreEarliestRemovalTime = true.
        mHeadsUpManager.removeNotification(mEntry.key, true /* ignoreEarliestRemovalTime */);

        // Check the initial state.
        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());
    }

    @Test
    public void testsTimeoutRemoval() {
        mHeadsUpManager.removeMinimumDisplayTimeForTesting();

        // Check the initial state.
        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        // Run the code on the main thready, not to run an async operations.
        instrumentation.runOnMainSync(() -> {
            // Add a notification.
            mHeadsUpManager.showNotification(mEntry);

            // Ensure the head up is visible before timeout.
            assertNotNull(mHeadsUpManager.getEntry(mEntry.key));
            assertNotNull(mHeadsUpManager.getTopEntry());
            assertEquals(1, mHeadsUpManager.getAllEntries().count());
            assertTrue(mHeadsUpManager.hasHeadsUpNotifications());
        });
        // Wait for the async operations, which removes the heads up notification.
        waitForIdleSync();

        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());
    }

    @Test
    public void releaseImmediately() {
        // Check the initial state.
        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());

        // Add a notification.
        mHeadsUpManager.showNotification(mEntry);

        assertEquals(mEntry, mHeadsUpManager.getEntry(mEntry.key));
        assertEquals(mEntry, mHeadsUpManager.getTopEntry());
        assertEquals(1, mHeadsUpManager.getAllEntries().count());
        assertTrue(mHeadsUpManager.hasHeadsUpNotifications());

        // Remove but defer, since the notification is visible on display.
        mHeadsUpManager.releaseImmediately(mEntry.key);

        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());
    }

    @Test
    public void releaseAllImmediately() {
        // Check the initial state.
        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());

        // Add a notification.
        mHeadsUpManager.showNotification(mEntry);

        assertEquals(mEntry, mHeadsUpManager.getEntry(mEntry.key));
        assertEquals(mEntry, mHeadsUpManager.getTopEntry());
        assertEquals(1, mHeadsUpManager.getAllEntries().count());
        assertTrue(mHeadsUpManager.hasHeadsUpNotifications());

        // Remove but defer, since the notification is visible on display.
        mHeadsUpManager.releaseAllImmediately();

        assertNull(mHeadsUpManager.getEntry(mEntry.key));
        assertNull(mHeadsUpManager.getTopEntry());
        assertEquals(0, mHeadsUpManager.getAllEntries().count());
        assertFalse(mHeadsUpManager.hasHeadsUpNotifications());
    }
}
