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

import static android.app.Notification.VISIBILITY_PUBLIC;
import static android.app.Notification.VISIBILITY_SECRET;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardCoordinatorTest extends SysuiTestCase {
    private static final int NOTIF_USER_ID = 0;
    private static final int CURR_USER_ID = 1;

    @Mock private Handler mMainHandler;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private HighPriorityProvider mHighPriorityProvider;
    @Mock private NotifPipeline mNotifPipeline;

    private NotificationEntry mEntry;
    private KeyguardCoordinator mKeyguardCoordinator;
    private NotifFilter mKeyguardFilter;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mKeyguardCoordinator = new KeyguardCoordinator(
                mContext, mMainHandler, mKeyguardStateController, mLockscreenUserManager,
                mBroadcastDispatcher, mStatusBarStateController,
                mKeyguardUpdateMonitor, mHighPriorityProvider);

        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .build();

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        mKeyguardCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(1)).addFinalizeFilter(filterCaptor.capture());
        mKeyguardFilter = filterCaptor.getValue();
    }

    @Test
    public void unfilteredState() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // THEN don't filter out the entry
        assertFalse(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void keyguardNotShowing() {
        // GIVEN the lockscreen isn't showing
        setupUnfilteredState(mEntry);
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        // THEN don't filter out the entry
        assertFalse(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void doNotShowLockscreenNotifications() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN we shouldn't show any lockscreen notifications
        when(mLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(false);

        // THEN filter out the entry
        assertTrue(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void lockdown() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification's user is in lockdown:
        when(mKeyguardUpdateMonitor.isUserInLockdown(NOTIF_USER_ID)).thenReturn(true);

        // THEN filter out the entry
        assertTrue(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void publicMode_settingsDisallow() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification's user is in public mode and settings are configured to disallow
        // notifications in public mode
        when(mLockscreenUserManager.isLockscreenPublicMode(NOTIF_USER_ID)).thenReturn(true);
        when(mLockscreenUserManager.userAllowsNotificationsInPublic(NOTIF_USER_ID))
                .thenReturn(false);

        // THEN filter out the entry
        assertTrue(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void publicMode_notifDisallowed() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification's user is in public mode and settings are configured to disallow
        // notifications in public mode
        when(mLockscreenUserManager.isLockscreenPublicMode(CURR_USER_ID)).thenReturn(true);
        mEntry.setRanking(new RankingBuilder()
                .setKey(mEntry.getKey())
                .setVisibilityOverride(VISIBILITY_SECRET).build());

        // THEN filter out the entry
        assertTrue(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void doesNotExceedThresholdToShow() {
        // GIVEN an 'unfiltered-keyguard-showing' state
        setupUnfilteredState(mEntry);

        // WHEN the notification doesn't exceed the threshold to show on the lockscreen
        mEntry.setRanking(new RankingBuilder()
                .setKey(mEntry.getKey())
                .setImportance(IMPORTANCE_MIN)
                .build());

        // THEN filter out the entry
        assertTrue(mKeyguardFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void summaryExceedsThresholdToShow() {
        // GIVEN the notification doesn't exceed the threshold to show on the lockscreen
        // but it's part of a group (has a parent)
        final GroupEntry parent = new GroupEntry("test_group_key");
        final NotificationEntry entryWithParent = new NotificationEntryBuilder()
                .setParent(parent)
                .setUser(new UserHandle(NOTIF_USER_ID))
                .build();

        setupUnfilteredState(entryWithParent);
        entryWithParent.setRanking(new RankingBuilder()
                .setKey(entryWithParent.getKey())
                .setImportance(IMPORTANCE_MIN)
                .build());

        // WHEN its parent has a summary that exceeds threshold to show on lockscreen
        parent.setSummary(new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_HIGH)
                .build());

        // THEN don't filter out the entry
        assertFalse(mKeyguardFilter.shouldFilterOut(entryWithParent, 0));

        // WHEN its parent has a summary that doesn't exceed threshold to show on lockscreen
        parent.setSummary(new NotificationEntryBuilder()
                .setImportance(IMPORTANCE_MIN)
                .build());

        // THEN filter out the entry
        assertTrue(mKeyguardFilter.shouldFilterOut(entryWithParent, 0));
    }

    /**
     * setup a state where the notification will not be filtered by the
     * KeyguardNotificationCoordinator when the keyguard is showing.
     */
    private void setupUnfilteredState(NotificationEntry entry) {
        // keyguard is showing
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        // show notifications on the lockscreen
        when(mLockscreenUserManager.shouldShowLockscreenNotifications()).thenReturn(true);

        // neither the current user nor the notification's user is in lockdown
        when(mLockscreenUserManager.getCurrentUserId()).thenReturn(CURR_USER_ID);
        when(mKeyguardUpdateMonitor.isUserInLockdown(NOTIF_USER_ID)).thenReturn(false);
        when(mKeyguardUpdateMonitor.isUserInLockdown(CURR_USER_ID)).thenReturn(false);

        // not in public mode
        when(mLockscreenUserManager.isLockscreenPublicMode(CURR_USER_ID)).thenReturn(false);
        when(mLockscreenUserManager.isLockscreenPublicMode(NOTIF_USER_ID)).thenReturn(false);

        // entry's ranking - should show on all lockscreens
        // + priority of the notification exceeds the threshold to be shown on the lockscreen
        entry.setRanking(new RankingBuilder()
                .setKey(mEntry.getKey())
                .setVisibilityOverride(VISIBILITY_PUBLIC)
                .setImportance(IMPORTANCE_HIGH)
                .build());

        // settings allows notifications in public mode
        when(mLockscreenUserManager.userAllowsNotificationsInPublic(CURR_USER_ID)).thenReturn(true);
        when(mLockscreenUserManager.userAllowsNotificationsInPublic(NOTIF_USER_ID))
                .thenReturn(true);

        // notification doesn't have a summary
    }
}
