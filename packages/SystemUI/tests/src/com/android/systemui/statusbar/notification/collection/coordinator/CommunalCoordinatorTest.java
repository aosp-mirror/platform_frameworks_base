/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.CommunalStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@SmallTest
public class CommunalCoordinatorTest extends SysuiTestCase {
    @Mock
    CommunalStateController mCommunalStateController;
    @Mock
    NotificationEntryManager mNotificationEntryManager;
    @Mock
    NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock
    NotifPipeline mNotifPipeline;
    @Mock
    NotificationEntry mNotificationEntry;
    @Mock
    Pluggable.PluggableListener mFilterListener;

    CommunalCoordinator mCoordinator;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mCoordinator = new CommunalCoordinator(mNotificationEntryManager,
                mNotificationLockscreenUserManager, mCommunalStateController);
    }

    @Test
    public void testNotificationSuppressionInCommunal() {
        mCoordinator.attach(mNotifPipeline);
        final ArgumentCaptor<CommunalStateController.Callback> stateCallbackCaptor =
                ArgumentCaptor.forClass(CommunalStateController.Callback.class);
        verify(mCommunalStateController).addCallback(stateCallbackCaptor.capture());

        final CommunalStateController.Callback stateCallback = stateCallbackCaptor.getValue();

        final ArgumentCaptor<NotifFilter> filterCaptor =
                ArgumentCaptor.forClass(NotifFilter.class);
        verify(mNotifPipeline).addPreGroupFilter(filterCaptor.capture());

        final NotifFilter filter = filterCaptor.getValue();

        // Verify that notifications are not filtered out by default.
        assert (!filter.shouldFilterOut(mNotificationEntry, 0));

        filter.setInvalidationListener(mFilterListener);

        // Verify that notifications are filtered out when communal is showing and that the filter
        // pipeline is notified.
        stateCallback.onCommunalViewShowingChanged();
        verify(mFilterListener).onPluggableInvalidated(any());
        verify(mNotificationEntryManager).updateNotifications(any());
        assert (filter.shouldFilterOut(mNotificationEntry, 0));

    }
}
