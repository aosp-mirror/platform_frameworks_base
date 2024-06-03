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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RankingCoordinatorTest extends SysuiTestCase {

    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private HighPriorityProvider mHighPriorityProvider;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private NodeController mAlertingHeaderController;
    @Mock private NodeController mSilentNodeController;
    @Mock private SectionHeaderController mSilentHeaderController;
    @Mock private Pluggable.PluggableListener<NotifFilter> mInvalidationListener;

    @Captor private ArgumentCaptor<NotifFilter> mNotifFilterCaptor;
    @Captor private ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;

    private NotificationEntry mEntry;
    private NotifFilter mCapturedSuspendedFilter;
    private NotifFilter mCapturedDozingFilter;
    private StatusBarStateController.StateListener mStatusBarStateCallback;
    private RankingCoordinator mRankingCoordinator;

    private NotifSectioner mAlertingSectioner;
    private NotifSectioner mSilentSectioner;
    private NotifSectioner mMinimizedSectioner;
    private ArrayList<NotifSectioner> mSections = new ArrayList<>(3);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mRankingCoordinator = new RankingCoordinator(
                mStatusBarStateController,
                mHighPriorityProvider,
                mAlertingHeaderController,
                mSilentHeaderController,
                mSilentNodeController);
        mEntry = spy(new NotificationEntryBuilder().build());
        mEntry.setRanking(getRankingForUnfilteredNotif().build());

        mRankingCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(2)).addPreGroupFilter(mNotifFilterCaptor.capture());
        mCapturedSuspendedFilter = mNotifFilterCaptor.getAllValues().get(0);
        mCapturedDozingFilter = mNotifFilterCaptor.getAllValues().get(1);
        mCapturedDozingFilter.setInvalidationListener(mInvalidationListener);

        verify(mStatusBarStateController, times(1)).addCallback(mStateListenerCaptor.capture());
        mStatusBarStateCallback = mStateListenerCaptor.getAllValues().get(0);

        mAlertingSectioner = mRankingCoordinator.getAlertingSectioner();
        mSilentSectioner = mRankingCoordinator.getSilentSectioner();
        mMinimizedSectioner = mRankingCoordinator.getMinimizedSectioner();
        mSections.addAll(Arrays.asList(mAlertingSectioner, mSilentSectioner, mMinimizedSectioner));
    }

    @Test
    public void testSilentHeaderClearableChildrenUpdate() {
        ListEntry listEntry = new ListEntry(mEntry.getKey(), 0L) {
            @Nullable
            @Override
            public NotificationEntry getRepresentativeEntry() {
                return mEntry;
            }
        };
        setRankingAmbient(false);
        setSbnClearable(true);
        mSilentSectioner.onEntriesUpdated(Arrays.asList(listEntry));
        verify(mSilentHeaderController).setClearSectionEnabled(eq(true));

        setSbnClearable(false);
        mSilentSectioner.onEntriesUpdated(Arrays.asList(listEntry));
        verify(mSilentHeaderController).setClearSectionEnabled(eq(false));
    }

    @Test
    public void testUnfilteredState() {
        // GIVEN no suppressed visual effects + app not suspended
        mEntry.setRanking(getRankingForUnfilteredNotif().build());

        // THEN don't filter out the notification
        assertFalse(mCapturedSuspendedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterSuspended() {
        // GIVEN the notification's app is suspended
        mEntry.setRanking(getRankingForUnfilteredNotif()
                .setSuspended(true)
                .build());

        // THEN filter out the notification
        assertTrue(mCapturedSuspendedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterDozingSuppressAmbient() {
        // GIVEN should suppress ambient
        mEntry.setRanking(getRankingForUnfilteredNotif()
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_AMBIENT)
                .build());

        // WHEN it's dozing (on ambient display)
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        // THEN filter out the notification
        assertTrue(mCapturedDozingFilter.shouldFilterOut(mEntry, 0));

        // WHEN it's not dozing (showing the notification list)
        when(mStatusBarStateController.isDozing()).thenReturn(false);

        // THEN don't filter out the notification
        assertFalse(mCapturedDozingFilter.shouldFilterOut(mEntry, 0));

        // WHEN it's not dozing and doze amount is 1
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        when(mStatusBarStateController.getDozeAmount()).thenReturn(1f);

        // THEN filter out the notification
        assertTrue(mCapturedDozingFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void filterDozingSuppressNotificationList() {
        // GIVEN should suppress from the notification list
        mEntry.setRanking(getRankingForUnfilteredNotif()
                .setSuppressedVisualEffects(SUPPRESSED_EFFECT_NOTIFICATION_LIST)
                .build());

        // WHEN it's dozing (on ambient display)
        when(mStatusBarStateController.isDozing()).thenReturn(true);

        // THEN don't filter out the notification
        assertFalse(mCapturedDozingFilter.shouldFilterOut(mEntry, 0));

        // WHEN it's not dozing (showing the notification list)
        when(mStatusBarStateController.isDozing()).thenReturn(false);
        
        // THEN filter out the notification
        assertTrue(mCapturedDozingFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testIncludeInSectionAlerting() {
        // GIVEN the entry is high priority
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(true);

        // THEN entry is in the alerting section
        assertTrue(mAlertingSectioner.isInSection(mEntry));
        assertFalse(mSilentSectioner.isInSection(mEntry));
    }

    @Test
    public void testIncludeInSectionSilent() {
        // GIVEN the entry isn't high priority
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setRankingAmbient(false);

        // THEN entry is in the silent section
        assertFalse(mAlertingSectioner.isInSection(mEntry));
        assertTrue(mSilentSectioner.isInSection(mEntry));
    }

    @Test
    public void testMinSection() {
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setRankingAmbient(true);
        assertInSection(mEntry, mMinimizedSectioner);
    }

    @Test
    public void testSilentSection() {
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setRankingAmbient(false);
        assertInSection(mEntry, mSilentSectioner);
    }

    @Test
    public void testClearableSilentSection() {
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setSbnClearable(true);
        setRankingAmbient(false);
        mSilentSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        verify(mSilentHeaderController).setClearSectionEnabled(eq(true));
    }

    @Test
    public void testClearableMinimizedSection() {
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setSbnClearable(true);
        setRankingAmbient(true);
        mMinimizedSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        verify(mSilentHeaderController).setClearSectionEnabled(eq(true));
    }

    @Test
    public void testNotClearableSilentSection() {
        setSbnClearable(false);
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setRankingAmbient(false);
        mSilentSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        mMinimizedSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        mAlertingSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        verify(mSilentHeaderController, times(2)).setClearSectionEnabled(eq(false));
    }

    @Test
    public void testNotClearableMinimizedSection() {
        setSbnClearable(false);
        when(mHighPriorityProvider.isHighPriority(mEntry)).thenReturn(false);
        setRankingAmbient(true);
        mSilentSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        mMinimizedSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        mAlertingSectioner.onEntriesUpdated(Arrays.asList(mEntry));
        verify(mSilentHeaderController, times(2)).setClearSectionEnabled(eq(false));
    }

    @Test
    public void statusBarStateCallbackTest() {
        mStatusBarStateCallback.onDozeAmountChanged(1f, 1f);
        verify(mInvalidationListener, times(1))
                .onPluggableInvalidated(mCapturedDozingFilter, "dozeAmount changed to one");
        reset(mInvalidationListener);

        mStatusBarStateCallback.onDozeAmountChanged(1f, 1f);
        verify(mInvalidationListener, never()).onPluggableInvalidated(any(), any());
        reset(mInvalidationListener);

        mStatusBarStateCallback.onDozeAmountChanged(0.6f, 0.6f);
        verify(mInvalidationListener, times(1))
                .onPluggableInvalidated(mCapturedDozingFilter, "dozeAmount changed to not one");
        reset(mInvalidationListener);

        mStatusBarStateCallback.onDozeAmountChanged(0f, 0f);
        verify(mInvalidationListener, never()).onPluggableInvalidated(any(), any());
        reset(mInvalidationListener);
    }

    private void assertInSection(NotificationEntry entry, NotifSectioner section) {
        for (NotifSectioner current: mSections) {
            if (current == section) {
                assertTrue(current.isInSection(entry));
            } else {
                assertFalse(current.isInSection(entry));
            }
        }
    }

    private RankingBuilder getRankingForUnfilteredNotif() {
        return new RankingBuilder(mEntry.getRanking())
                .setChannel(new NotificationChannel("id", null, IMPORTANCE_DEFAULT))
                .setSuppressedVisualEffects(0)
                .setSuspended(false);
    }

    private void setSbnClearable(boolean clearable) {
        mEntry.setSbn(new SbnBuilder(mEntry.getSbn())
                .setFlag(mContext, Notification.FLAG_NO_CLEAR, !clearable)
                .build());
        assertEquals(clearable, mEntry.getSbn().isClearable());
    }

    private void setRankingAmbient(boolean ambient) {
        mEntry.setRanking(new RankingBuilder(mEntry.getRanking())
                .setImportance(ambient
                        ? NotificationManager.IMPORTANCE_MIN
                        : IMPORTANCE_DEFAULT)
                .build());
        assertEquals(ambient, mEntry.getRanking().isAmbient());
    }
}
