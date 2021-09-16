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

import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.collection.render.NodeController;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class RankingCoordinatorTest extends SysuiTestCase {

    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private HighPriorityProvider mHighPriorityProvider;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private NodeController mAlertingHeaderController;
    @Mock private NodeController mSilentNodeController;
    @Mock private SectionHeaderController mSilentHeaderController;

    @Captor private ArgumentCaptor<NotifFilter> mNotifFilterCaptor;

    private NotificationEntry mEntry;
    private NotifFilter mCapturedSuspendedFilter;
    private NotifFilter mCapturedDozingFilter;

    private NotifSectioner mAlertingSectioner;
    private NotifSectioner mSilentSectioner;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        RankingCoordinator rankingCoordinator = new RankingCoordinator(
                mStatusBarStateController, mHighPriorityProvider, mAlertingHeaderController,
                mSilentHeaderController, mSilentNodeController);
        mEntry = new NotificationEntryBuilder().build();

        rankingCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(2)).addPreGroupFilter(mNotifFilterCaptor.capture());
        mCapturedSuspendedFilter = mNotifFilterCaptor.getAllValues().get(0);
        mCapturedDozingFilter = mNotifFilterCaptor.getAllValues().get(1);

        mAlertingSectioner = rankingCoordinator.getAlertingSectioner();
        mSilentSectioner = rankingCoordinator.getSilentSectioner();
    }

    @Test
    public void testSilentHeaderClearableChildrenUpdate() {
        StatusBarNotification sbn = Mockito.mock(StatusBarNotification.class);
        Mockito.doReturn("key").when(sbn).getKey();
        Mockito.doReturn(Mockito.mock(Notification.class)).when(sbn).getNotification();
        NotificationEntry entry = new NotificationEntryBuilder().setSbn(sbn).build();
        ListEntry listEntry = new ListEntry("key", 0L) {
            @Nullable
            @Override
            public NotificationEntry getRepresentativeEntry() {
                return entry;
            }
        };
        Mockito.doReturn(true).when(sbn).isClearable();
        mSilentSectioner.onEntriesUpdated(Arrays.asList(listEntry));
        verify(mSilentHeaderController).setClearSectionEnabled(eq(true));

        Mockito.doReturn(false).when(sbn).isClearable();
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

        // THEN entry is in the silent section
        assertFalse(mAlertingSectioner.isInSection(mEntry));
        assertTrue(mSilentSectioner.isInSection(mEntry));
    }

    private RankingBuilder getRankingForUnfilteredNotif() {
        return new RankingBuilder()
                .setKey(mEntry.getKey())
                .setSuppressedVisualEffects(0)
                .setSuspended(false);
    }
}
