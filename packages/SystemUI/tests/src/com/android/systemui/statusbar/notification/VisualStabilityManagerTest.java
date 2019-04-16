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

import static junit.framework.Assert.assertEquals;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.notification.VisualStabilityManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VisualStabilityManagerTest extends SysuiTestCase {

    private VisualStabilityManager mVisualStabilityManager = new VisualStabilityManager();
    private VisualStabilityManager.Callback mCallback = mock(VisualStabilityManager.Callback.class);
    private VisibilityLocationProvider mLocationProvider = mock(VisibilityLocationProvider.class);
    private ExpandableNotificationRow mRow = mock(ExpandableNotificationRow.class);
    private NotificationData.Entry mEntry;

    @Before
    public void setUp() {
        mVisualStabilityManager.setVisibilityLocationProvider(mLocationProvider);
        mEntry = new NotificationData.Entry(mock(StatusBarNotification.class));
        mEntry.row = mRow;
    }

    @Test
    public void testPanelExpansion() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
        mVisualStabilityManager.setPanelExpanded(false);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    @Test
    public void testScreenOn() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
        mVisualStabilityManager.setScreenOn(false);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    @Test
    public void testReorderingAllowedChangesScreenOn() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), false);
        mVisualStabilityManager.setScreenOn(false);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), true);
    }

    @Test
    public void testReorderingAllowedChangesPanel() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), false);
        mVisualStabilityManager.setPanelExpanded(false);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), true);
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
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    @Test
    public void testReorderingVisibleHeadsUpNotAllowed() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(anyObject())).thenReturn(true);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
    }

    @Test
    public void testReorderingVisibleHeadsUpAllowed() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(anyObject())).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    @Test
    public void testReorderingVisibleHeadsUpAllowedOnce() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(anyObject())).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        mVisualStabilityManager.onReorderingFinished();
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
    }

    @Test
    public void testPulsing() {
        mVisualStabilityManager.setPulsing(true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
        mVisualStabilityManager.setPulsing(false);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    @Test
    public void testReorderingAllowedChanges_Pulsing() {
        mVisualStabilityManager.setPulsing(true);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), false);
        mVisualStabilityManager.setPulsing(false);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), true);
    }

    @Test
    public void testCallBackCalled_Pulsing() {
        mVisualStabilityManager.setPulsing(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setPulsing(false);
        verify(mCallback).onReorderingAllowed();
    }
}
