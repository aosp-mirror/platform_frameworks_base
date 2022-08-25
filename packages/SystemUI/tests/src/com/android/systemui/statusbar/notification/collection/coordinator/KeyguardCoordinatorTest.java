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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.SectionHeaderVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * TODO(b/224771204) Create test cases
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@Ignore
public class KeyguardCoordinatorTest extends SysuiTestCase {
    private static final int NOTIF_USER_ID = 0;
    private static final int CURR_USER_ID = 1;

    @Mock private Handler mMainHandler;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private HighPriorityProvider mHighPriorityProvider;
    @Mock private SectionHeaderVisibilityProvider mSectionHeaderVisibilityProvider;
    @Mock private NotifPipeline mNotifPipeline;
    @Mock private KeyguardNotificationVisibilityProvider mKeyguardNotificationVisibilityProvider;

    private NotificationEntry mEntry;
    private NotifFilter mKeyguardFilter;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        KeyguardCoordinator keyguardCoordinator = new KeyguardCoordinator(
                mStatusBarStateController,
                mKeyguardUpdateMonitor, mHighPriorityProvider, mSectionHeaderVisibilityProvider,
                mKeyguardNotificationVisibilityProvider, mock(SharedCoordinatorLogger.class));

        mEntry = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID))
                .build();

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        keyguardCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(1)).addFinalizeFilter(filterCaptor.capture());
        mKeyguardFilter = filterCaptor.getValue();
    }
}
