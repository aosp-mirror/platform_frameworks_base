/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class VisualStabilityManagerTest extends SysuiTestCase {

    private TestableLooper mTestableLooper;

    private VisualStabilityManager mVisualStabilityManager;
    private VisualStabilityManager.Callback mCallback = mock(VisualStabilityManager.Callback.class);
    private VisibilityLocationProvider mLocationProvider = mock(VisibilityLocationProvider.class);
    private ExpandableNotificationRow mRow = mock(ExpandableNotificationRow.class);
    private NotificationEntry mEntry;

    @Before
    public void setUp() {
        mTestableLooper = TestableLooper.get(this);
        mVisualStabilityManager = new VisualStabilityManager(
                mock(NotificationEntryManager.class),
                new Handler(mTestableLooper.getLooper()));

        mVisualStabilityManager.setUpWithPresenter(mock(NotificationPresenter.class));
        mVisualStabilityManager.setVisibilityLocationProvider(mLocationProvider);
        mEntry = new NotificationEntry(mock(StatusBarNotification.class));
        mEntry.setRow(mRow);

        when(mRow.getEntry()).thenReturn(mEntry);
    }

    @Test
    public void testPanelExpansion() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
        mVisualStabilityManager.setPanelExpanded(false);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testScreenOn() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
        mVisualStabilityManager.setScreenOn(false);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingAllowedChangesScreenOn() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
        mVisualStabilityManager.setScreenOn(false);
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testReorderingAllowedChangesPanel() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
        mVisualStabilityManager.setPanelExpanded(false);
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testCallBackCalledScreenOn() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setScreenOn(false);
        verify(mCallback).onReorderingAllowed();
    }

    @Test
    public void testCallBackCalledPanelExpanded() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setPanelExpanded(false);
        verify(mCallback).onReorderingAllowed();
    }

    @Test
    public void testCallBackExactlyOnce() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setScreenOn(false);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.setScreenOn(false);
        verify(mCallback).onReorderingAllowed();
    }

    @Test
    public void testAddedCanReorder() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.notifyViewAddition(mRow);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingVisibleHeadsUpNotAllowed() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(any(NotificationEntry.class))).thenReturn(true);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingVisibleHeadsUpAllowed() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(any(NotificationEntry.class))).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingVisibleHeadsUpAllowedOnce() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(any(NotificationEntry.class))).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        mVisualStabilityManager.onReorderingFinished();
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testPulsing() {
        mVisualStabilityManager.setPulsing(true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
        mVisualStabilityManager.setPulsing(false);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingAllowedChanges_Pulsing() {
        mVisualStabilityManager.setPulsing(true);
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
        mVisualStabilityManager.setPulsing(false);
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testCallBackCalled_Pulsing() {
        mVisualStabilityManager.setPulsing(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setPulsing(false);
        verify(mCallback).onReorderingAllowed();
    }

    @Test
    public void testTemporarilyAllowReorderingNotifiesCallbacks() {
        // GIVEN having the panel open (which would block reordering)
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);

        // WHEN we temprarily allow reordering
        mVisualStabilityManager.temporarilyAllowReordering();

        // THEN callbacks are notified that reordering is allowed
        verify(mCallback).onReorderingAllowed();
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testTemporarilyAllowReorderingDoesntOverridePulsing() {
        // GIVEN we are in a pulsing state
        mVisualStabilityManager.setPulsing(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);

        // WHEN we temprarily allow reordering
        mVisualStabilityManager.temporarilyAllowReordering();

        // THEN reordering is still not allowed
        verify(mCallback, never()).onReorderingAllowed();
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testTemporarilyAllowReorderingExpires() {
        // GIVEN having the panel open (which would block reordering)
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);

        // WHEN we temprarily allow reordering and then wait until the window expires
        mVisualStabilityManager.temporarilyAllowReordering();
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
        mTestableLooper.processMessages(1);

        // THEN reordering is no longer allowed
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
    }
}
