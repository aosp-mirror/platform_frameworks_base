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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable.PluggableListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class HideNotifsForOtherUsersCoordinatorTest extends SysuiTestCase {

    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private PluggableListener<NotifFilter> mInvalidationListener;

    @Captor private ArgumentCaptor<UserChangedListener> mUserChangedListenerCaptor;
    @Captor private ArgumentCaptor<NotifFilter> mNotifFilterCaptor;

    private UserChangedListener mCapturedUserChangeListener;
    private NotifFilter mCapturedNotifFilter;

    private NotificationEntry mEntry = new NotificationEntryBuilder().build();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        HideNotifsForOtherUsersCoordinator coordinator =
                new HideNotifsForOtherUsersCoordinator(mLockscreenUserManager);
        coordinator.attach(mNotifPipeline);

        verify(mLockscreenUserManager).addUserChangedListener(mUserChangedListenerCaptor.capture());
        verify(mNotifPipeline).addPreGroupFilter(mNotifFilterCaptor.capture());

        mCapturedUserChangeListener = mUserChangedListenerCaptor.getValue();
        mCapturedNotifFilter = mNotifFilterCaptor.getValue();

        mCapturedNotifFilter.setInvalidationListener(mInvalidationListener);
    }

    @Test
    public void testFilterOutNotifsFromOtherProfiles() {
        // GIVEN that all notifs are NOT for the current user
        when(mLockscreenUserManager.isCurrentProfile(anyInt())).thenReturn(false);

        // THEN they should all be filtered out
        assertTrue(mCapturedNotifFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testPreserveNotifsFromThisProfile() {
        // GIVEN that all notifs ARE for the current user
        when(mLockscreenUserManager.isCurrentProfile(anyInt())).thenReturn(true);

        // THEN none should be filtered out
        assertFalse(mCapturedNotifFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void testFilterIsInvalidatedWhenProfilesChange() {
        // WHEN the current user profiles change
        mCapturedUserChangeListener.onCurrentProfilesChanged(new SparseArray<>());

        // THEN the filter is invalidated
        verify(mInvalidationListener).onPluggableInvalidated(eq(mCapturedNotifFilter), any());
    }
}
