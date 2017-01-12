/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.notification;

import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.notification.VisibilityLocationProvider;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertEquals;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SmallTest
public class VisualStabilityManagerTest extends SysuiTestCase {

    private VisualStabilityManager mVisualStabilityManager = new VisualStabilityManager();
    private VisualStabilityManager.Callback mCallback = mock(VisualStabilityManager.Callback.class);
    private VisibilityLocationProvider mLocationProvider = mock(VisibilityLocationProvider.class);
    private ExpandableNotificationRow mRow = mock(ExpandableNotificationRow.class);
    private NotificationData.Entry mEntry;

    @Before
    public void setUp() {
        mVisualStabilityManager.setVisibilityLocationProvider(mLocationProvider);
        mEntry = new NotificationData.Entry(mock(StatusBarNotification.class),
                mock(StatusBarIconView.class));
        mEntry.row = mRow;
    }

    public void testPanelExpansion() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
        mVisualStabilityManager.setPanelExpanded(false);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

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

    public void testReorderingAllowedChangesPanel() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), false);
        mVisualStabilityManager.setPanelExpanded(false);
        assertEquals(mVisualStabilityManager.isReorderingAllowed(), true);
    }

    public void testCallBackCalledScreenOn() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setScreenOn(false);
        verify(mCallback).onReorderingAllowed();
    }

    public void testCallBackCalledPanelExpanded() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setPanelExpanded(false);
        verify(mCallback).onReorderingAllowed();
    }

    public void testCallBackExactlyOnce() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback);
        mVisualStabilityManager.setScreenOn(false);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.setScreenOn(false);
        verify(mCallback).onReorderingAllowed();
    }

    public void testAddedCanReorder() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        mVisualStabilityManager.notifyViewAddition(mRow);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    public void testReorderingVisibleHeadsUpNotAllowed() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(anyObject())).thenReturn(true);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
    }

    public void testReorderingVisibleHeadsUpAllowed() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(anyObject())).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), true);
    }

    public void testReorderingVisibleHeadsUpAllowedOnce() {
        mVisualStabilityManager.setPanelExpanded(true);
        mVisualStabilityManager.setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(anyObject())).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        mVisualStabilityManager.onReorderingFinished();
        assertEquals(mVisualStabilityManager.canReorderNotification(mRow), false);
    }
}
