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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

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

    private StatusBarStateController.StateListener mStatusBarStateListener;
    private WakefulnessLifecycle.Observer mWakefulnessObserver;

    @Before
    public void setUp() {
        StatusBarStateController statusBarStateController = mock(StatusBarStateController.class);
        WakefulnessLifecycle wakefulnessLifecycle = mock(WakefulnessLifecycle.class);

        mTestableLooper = TestableLooper.get(this);
        mVisualStabilityManager = new VisualStabilityManager(
                mock(NotificationEntryManager.class),
                new Handler(mTestableLooper.getLooper()),
                statusBarStateController,
                wakefulnessLifecycle,
                mock(DumpManager.class));

        mVisualStabilityManager.setVisibilityLocationProvider(mLocationProvider);
        mEntry = new NotificationEntryBuilder().build();
        mEntry.setRow(mRow);

        when(mRow.getEntry()).thenReturn(mEntry);

        ArgumentCaptor<StatusBarStateController.StateListener> stateListenerCaptor =
                ArgumentCaptor.forClass(StatusBarStateController.StateListener.class);
        verify(statusBarStateController).addCallback(stateListenerCaptor.capture());
        mStatusBarStateListener = stateListenerCaptor.getValue();

        ArgumentCaptor<WakefulnessLifecycle.Observer> wakefulnessObserverCaptor =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        verify(wakefulnessLifecycle).addObserver(wakefulnessObserverCaptor.capture());
        mWakefulnessObserver = wakefulnessObserverCaptor.getValue();
    }

    @Test
    public void testPanelExpansion() {
        setPanelExpanded(true);
        setScreenOn(true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
        setPanelExpanded(false);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testScreenOn() {
        setPanelExpanded(true);
        setScreenOn(true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
        setScreenOn(false);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingAllowedChangesScreenOn() {
        setPanelExpanded(true);
        setScreenOn(true);
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
        setScreenOn(false);
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testReorderingAllowedChangesPanel() {
        setPanelExpanded(true);
        setScreenOn(true);
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
        setPanelExpanded(false);
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testCallBackCalledScreenOn() {
        setPanelExpanded(true);
        setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);
        setScreenOn(false);
        verify(mCallback).onChangeAllowed();
    }

    @Test
    public void testCallBackCalledPanelExpanded() {
        setPanelExpanded(true);
        setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);
        setPanelExpanded(false);
        verify(mCallback).onChangeAllowed();
    }

    @Test
    public void testCallBackExactlyOnce() {
        setPanelExpanded(true);
        setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);
        setScreenOn(false);
        setScreenOn(true);
        setScreenOn(false);
        verify(mCallback).onChangeAllowed();
    }

    @Test
    public void testCallBackCalledContinuouslyWhenRequested() {
        setPanelExpanded(true);
        setScreenOn(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, true  /* persistent */);
        setScreenOn(false);
        setScreenOn(true);
        setScreenOn(false);
        verify(mCallback, times(2)).onChangeAllowed();
    }

    @Test
    public void testAddedCanReorder() {
        setPanelExpanded(true);
        setScreenOn(true);
        mVisualStabilityManager.notifyViewAddition(mRow);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingVisibleHeadsUpNotAllowed() {
        setPanelExpanded(true);
        setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(any(NotificationEntry.class))).thenReturn(true);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingVisibleHeadsUpAllowed() {
        setPanelExpanded(true);
        setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(any(NotificationEntry.class))).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingVisibleHeadsUpAllowedOnce() {
        setPanelExpanded(true);
        setScreenOn(true);
        when(mLocationProvider.isInVisibleLocation(any(NotificationEntry.class))).thenReturn(false);
        mVisualStabilityManager.onHeadsUpStateChanged(mEntry, true);
        mVisualStabilityManager.onReorderingFinished();
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testPulsing() {
        setPulsing(true);
        assertFalse(mVisualStabilityManager.canReorderNotification(mRow));
        setPulsing(false);
        assertTrue(mVisualStabilityManager.canReorderNotification(mRow));
    }

    @Test
    public void testReorderingAllowedChanges_Pulsing() {
        setPulsing(true);
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
        setPulsing(false);
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testCallBackCalled_Pulsing() {
        setPulsing(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);
        setPulsing(false);
        verify(mCallback).onChangeAllowed();
    }

    @Test
    public void testTemporarilyAllowReorderingNotifiesCallbacks() {
        // GIVEN having the panel open (which would block reordering)
        setScreenOn(true);
        setPanelExpanded(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);

        // WHEN we temprarily allow reordering
        mVisualStabilityManager.temporarilyAllowReordering();

        // THEN callbacks are notified that reordering is allowed
        verify(mCallback).onChangeAllowed();
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testTemporarilyAllowReorderingDoesntOverridePulsing() {
        // GIVEN we are in a pulsing state
        setPulsing(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);

        // WHEN we temprarily allow reordering
        mVisualStabilityManager.temporarilyAllowReordering();

        // THEN reordering is still not allowed
        verify(mCallback, never()).onChangeAllowed();
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
    }

    @Test
    public void testTemporarilyAllowReorderingExpires() {
        // GIVEN having the panel open (which would block reordering)
        setScreenOn(true);
        setPanelExpanded(true);
        mVisualStabilityManager.addReorderingAllowedCallback(mCallback, false  /* persistent */);

        // WHEN we temprarily allow reordering and then wait until the window expires
        mVisualStabilityManager.temporarilyAllowReordering();
        assertTrue(mVisualStabilityManager.isReorderingAllowed());
        mTestableLooper.processMessages(1);

        // THEN reordering is no longer allowed
        assertFalse(mVisualStabilityManager.isReorderingAllowed());
    }

    private void setPanelExpanded(boolean expanded) {
        mStatusBarStateListener.onExpandedChanged(expanded);
    }

    private void setPulsing(boolean pulsing) {
        mStatusBarStateListener.onPulsingChanged(pulsing);
    }

    private void setScreenOn(boolean screenOn) {
        if (screenOn) {
            mWakefulnessObserver.onStartedWakingUp();
        } else {
            mWakefulnessObserver.onFinishedGoingToSleep();
        }
    }
}
