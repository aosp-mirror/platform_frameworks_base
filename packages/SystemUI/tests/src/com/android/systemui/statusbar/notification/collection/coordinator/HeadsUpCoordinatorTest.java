/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HeadsUpCoordinatorTest extends SysuiTestCase {

    private HeadsUpCoordinator mCoordinator;

    // captured listeners and pluggables:
    private NotifCollectionListener mCollectionListener;
    private NotifPromoter mNotifPromoter;
    private NotifLifetimeExtender mNotifLifetimeExtender;
    private OnHeadsUpChangedListener mOnHeadsUpChangedListener;
    private NotifSection mNotifSection;

    @Mock private NotifPipeline mNotifPipeline;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private HeadsUpViewBinder mHeadsUpViewBinder;
    @Mock private NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private RemoteInputController mRemoteInputController;
    @Mock private NotifLifetimeExtender.OnEndLifetimeExtensionCallback mEndLifetimeExtension;

    private NotificationEntry mEntry;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);

        mCoordinator = new HeadsUpCoordinator(
                mHeadsUpManager,
                mHeadsUpViewBinder,
                mNotificationInterruptStateProvider,
                mRemoteInputManager
        );

        mCoordinator.attach(mNotifPipeline);

        // capture arguments:
        ArgumentCaptor<NotifCollectionListener> notifCollectionCaptor =
                ArgumentCaptor.forClass(NotifCollectionListener.class);
        ArgumentCaptor<NotifPromoter> notifPromoterCaptor =
                ArgumentCaptor.forClass(NotifPromoter.class);
        ArgumentCaptor<NotifLifetimeExtender> notifLifetimeExtenderCaptor =
                ArgumentCaptor.forClass(NotifLifetimeExtender.class);
        ArgumentCaptor<OnHeadsUpChangedListener> headsUpChangedListenerCaptor =
                ArgumentCaptor.forClass(OnHeadsUpChangedListener.class);

        verify(mNotifPipeline).addCollectionListener(notifCollectionCaptor.capture());
        verify(mNotifPipeline).addPromoter(notifPromoterCaptor.capture());
        verify(mNotifPipeline).addNotificationLifetimeExtender(
                notifLifetimeExtenderCaptor.capture());
        verify(mHeadsUpManager).addListener(headsUpChangedListenerCaptor.capture());

        mCollectionListener = notifCollectionCaptor.getValue();
        mNotifPromoter = notifPromoterCaptor.getValue();
        mNotifLifetimeExtender = notifLifetimeExtenderCaptor.getValue();
        mOnHeadsUpChangedListener = headsUpChangedListenerCaptor.getValue();

        mNotifSection = mCoordinator.getSection();
        mNotifLifetimeExtender.setCallback(mEndLifetimeExtension);
        mEntry = new NotificationEntryBuilder().build();
    }

    @Test
    public void testPromotesCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        setCurrentHUN(mEntry);

        // THEN only promote the current HUN, mEntry
        assertTrue(mNotifPromoter.shouldPromoteToTopLevel(mEntry));
        assertFalse(mNotifPromoter.shouldPromoteToTopLevel(new NotificationEntryBuilder().build()));
    }

    @Test
    public void testIncludeInSectionCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        setCurrentHUN(mEntry);

        // THEN only section the current HUN, mEntry
        assertTrue(mNotifSection.isInSection(mEntry));
        assertFalse(mNotifSection.isInSection(new NotificationEntryBuilder().build()));
    }

    @Test
    public void testLifetimeExtendsCurrentHUN() {
        // GIVEN there is a HUN, mEntry
        setCurrentHUN(mEntry);

        // THEN only the current HUN, mEntry, should be lifetimeExtended
        assertTrue(mNotifLifetimeExtender.shouldExtendLifetime(mEntry, /* cancellationReason */ 0));
        assertFalse(mNotifLifetimeExtender.shouldExtendLifetime(
                new NotificationEntryBuilder().build(), /* cancellationReason */ 0));
    }

    @Test
    public void testLifetimeExtensionEndsOnNewHUN() {
        // GIVEN there was a HUN that was lifetime extended
        setCurrentHUN(mEntry);
        assertTrue(mNotifLifetimeExtender.shouldExtendLifetime(
                mEntry, /* cancellation reason */ 0));

        // WHEN there's a new HUN
        NotificationEntry newHUN = new NotificationEntryBuilder().build();
        setCurrentHUN(newHUN);

        // THEN the old entry's lifetime extension should be cancelled
        verify(mEndLifetimeExtension).onEndLifetimeExtension(mNotifLifetimeExtender, mEntry);
    }

    @Test
    public void testLifetimeExtensionEndsOnNoHUNs() {
        // GIVEN there was a HUN that was lifetime extended
        setCurrentHUN(mEntry);
        assertTrue(mNotifLifetimeExtender.shouldExtendLifetime(
                mEntry, /* cancellation reason */ 0));

        // WHEN there's no longer a HUN
        setCurrentHUN(null);

        // THEN the old entry's lifetime extension should be cancelled
        verify(mEndLifetimeExtension).onEndLifetimeExtension(mNotifLifetimeExtender, mEntry);
    }

    @Test
    public void testShowHUNOnInflationFinished() {
        // WHEN a notification should HUN and its inflation is finished
        when(mNotificationInterruptStateProvider.shouldHeadsUp(mEntry)).thenReturn(true);

        ArgumentCaptor<BindCallback> bindCallbackCaptor =
                ArgumentCaptor.forClass(BindCallback.class);
        mCollectionListener.onEntryAdded(mEntry);
        verify(mHeadsUpViewBinder).bindHeadsUpView(eq(mEntry), bindCallbackCaptor.capture());

        bindCallbackCaptor.getValue().onBindFinished(mEntry);

        // THEN we tell the HeadsUpManager to show the notification
        verify(mHeadsUpManager).showNotification(mEntry);
    }

    @Test
    public void testNoHUNOnInflationFinished() {
        // WHEN a notification shouldn't HUN and its inflation is finished
        when(mNotificationInterruptStateProvider.shouldHeadsUp(mEntry)).thenReturn(false);
        ArgumentCaptor<BindCallback> bindCallbackCaptor =
                ArgumentCaptor.forClass(BindCallback.class);
        mCollectionListener.onEntryAdded(mEntry);

        // THEN we never bind the heads up view or tell HeadsUpManager to show the notification
        verify(mHeadsUpViewBinder, never()).bindHeadsUpView(
                eq(mEntry), bindCallbackCaptor.capture());
        verify(mHeadsUpManager, never()).showNotification(mEntry);
    }

    @Test
    public void testOnEntryRemovedRemovesHeadsUpNotification() {
        // GIVEN the current HUN is mEntry
        setCurrentHUN(mEntry);

        // WHEN mEntry is removed from the notification collection
        mCollectionListener.onEntryRemoved(mEntry, /* cancellation reason */ 0);
        when(mRemoteInputController.isSpinning(any())).thenReturn(false);

        // THEN heads up manager should remove the entry
        verify(mHeadsUpManager).removeNotification(mEntry.getKey(), false);
    }

    private void setCurrentHUN(NotificationEntry entry) {
        when(mHeadsUpManager.getTopEntry()).thenReturn(entry);
        when(mHeadsUpManager.isAlerting(any())).thenReturn(false);
        if (entry != null) {
            when(mHeadsUpManager.isAlerting(entry.getKey())).thenReturn(true);
        }
        mOnHeadsUpChangedListener.onHeadsUpStateChanged(entry, entry != null);
    }
}
