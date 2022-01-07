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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.interruption.HeadsUpViewBinder;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

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
    private NotifSectioner mNotifSectioner;

    @Mock private NotifPipeline mNotifPipeline;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private HeadsUpViewBinder mHeadsUpViewBinder;
    @Mock private NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private NotifLifetimeExtender.OnEndLifetimeExtensionCallback mEndLifetimeExtension;
    @Mock private NodeController mHeaderController;

    private NotificationEntry mEntry;
    private final FakeSystemClock mClock = new FakeSystemClock();
    private final FakeExecutor mExecutor = new FakeExecutor(mClock);
    private final ArrayList<NotificationEntry> mHuns = new ArrayList();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCoordinator = new HeadsUpCoordinator(
                mHeadsUpManager,
                mHeadsUpViewBinder,
                mNotificationInterruptStateProvider,
                mRemoteInputManager,
                mHeaderController,
                mExecutor);

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

        given(mHeadsUpManager.getAllEntries()).willAnswer(i -> mHuns.stream());
        given(mHeadsUpManager.isAlerting(anyString())).willAnswer(i -> {
            String key = i.getArgument(0);
            for (NotificationEntry entry : mHuns) {
                if (entry.getKey().equals(key)) return true;
            }
            return false;
        });
        when(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L);

        mCollectionListener = notifCollectionCaptor.getValue();
        mNotifPromoter = notifPromoterCaptor.getValue();
        mNotifLifetimeExtender = notifLifetimeExtenderCaptor.getValue();
        mOnHeadsUpChangedListener = headsUpChangedListenerCaptor.getValue();

        mNotifSectioner = mCoordinator.getSectioner();
        mNotifLifetimeExtender.setCallback(mEndLifetimeExtension);
        mEntry = new NotificationEntryBuilder().build();
    }

    @Test
    public void testCancelStickyNotification() {
        when(mHeadsUpManager.isSticky(anyString())).thenReturn(true);
        addHUN(mEntry);
        when(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 0L);
        assertTrue(mNotifLifetimeExtender.shouldExtendLifetime(mEntry, 0));
        mClock.advanceTime(1000L);
        mExecutor.runAllReady();
        verify(mHeadsUpManager, times(1))
                .removeNotification(anyString(), eq(true));
    }

    @Test
    public void testCancelUpdatedStickyNotification() {
        when(mHeadsUpManager.isSticky(anyString())).thenReturn(true);
        addHUN(mEntry);
        when(mHeadsUpManager.getEarliestRemovalTime(anyString())).thenReturn(1000L, 500L);
        assertTrue(mNotifLifetimeExtender.shouldExtendLifetime(mEntry, 0));
        mClock.advanceTime(1000L);
        mExecutor.runAllReady();
        verify(mHeadsUpManager, times(0))
                .removeNotification(anyString(), eq(true));
    }

    @Test
    public void testPromotesCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        addHUN(mEntry);

        // THEN only promote the current HUN, mEntry
        assertTrue(mNotifPromoter.shouldPromoteToTopLevel(mEntry));
        assertFalse(mNotifPromoter.shouldPromoteToTopLevel(new NotificationEntryBuilder()
                .setPkg("test-package2")
                .build()));
    }

    @Test
    public void testIncludeInSectionCurrentHUN() {
        // GIVEN the current HUN is set to mEntry
        addHUN(mEntry);

        // THEN only section the current HUN, mEntry
        assertTrue(mNotifSectioner.isInSection(mEntry));
        assertFalse(mNotifSectioner.isInSection(new NotificationEntryBuilder()
                .setPkg("test-package")
                .build()));
    }

    @Test
    public void testLifetimeExtendsCurrentHUN() {
        // GIVEN there is a HUN, mEntry
        addHUN(mEntry);

        // THEN only the current HUN, mEntry, should be lifetimeExtended
        assertTrue(mNotifLifetimeExtender.shouldExtendLifetime(mEntry, /* cancellationReason */ 0));
        assertFalse(mNotifLifetimeExtender.shouldExtendLifetime(
                new NotificationEntryBuilder()
                        .setPkg("test-package")
                        .build(), /* cancellationReason */ 0));
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
        addHUN(mEntry);

        // WHEN mEntry is removed from the notification collection
        mCollectionListener.onEntryRemoved(mEntry, /* cancellation reason */ 0);
        when(mRemoteInputManager.isSpinning(any())).thenReturn(false);

        // THEN heads up manager should remove the entry
        verify(mHeadsUpManager).removeNotification(mEntry.getKey(), false);
    }

    private void addHUN(NotificationEntry entry) {
        mHuns.add(entry);
        when(mHeadsUpManager.getTopEntry()).thenReturn(entry);
        mOnHeadsUpChangedListener.onHeadsUpStateChanged(entry, entry != null);
    }
}
