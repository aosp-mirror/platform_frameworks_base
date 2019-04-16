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
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBarWindowManager;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Verifies that particular sets of dependencies don't have dependencies on others. For example,
 * code managing notifications shouldn't directly depend on StatusBar, since there are platforms
 * which want to manage notifications, but don't use StatusBar.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NonPhoneDependencyTest extends SysuiTestCase {
    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationListContainer mListContainer;
    @Mock private NotificationEntryManager.Callback mEntryManagerCallback;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private RemoteInputController.Delegate mDelegate;
    @Mock private NotificationInfo.CheckSaveListener mCheckSaveListener;
    @Mock private NotificationGutsManager.OnSettingsClickListener mOnClickListener;
    @Mock private NotificationRemoteInputManager.Callback mRemoteInputManagerCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mPresenter.getHandler()).thenReturn(Handler.createAsync(Looper.myLooper()));
    }

    @Test
    public void testNotificationManagementCodeHasNoDependencyOnStatusBarWindowManager() {
        NotificationEntryManager entryManager = Dependency.get(NotificationEntryManager.class);
        NotificationGutsManager gutsManager = Dependency.get(NotificationGutsManager.class);
        NotificationListener notificationListener = Dependency.get(NotificationListener.class);
        NotificationLogger notificationLogger = Dependency.get(NotificationLogger.class);
        NotificationMediaManager mediaManager = Dependency.get(NotificationMediaManager.class);
        NotificationRemoteInputManager remoteInputManager =
                Dependency.get(NotificationRemoteInputManager.class);
        NotificationLockscreenUserManager lockscreenUserManager =
                Dependency.get(NotificationLockscreenUserManager.class);
        NotificationViewHierarchyManager viewHierarchyManager =
                Dependency.get(NotificationViewHierarchyManager.class);

        when(mPresenter.getNotificationLockscreenUserManager()).thenReturn(lockscreenUserManager);
        when(mPresenter.getGroupManager()).thenReturn(
                Dependency.get(NotificationGroupManager.class));

        entryManager.setUpWithPresenter(mPresenter, mListContainer, mEntryManagerCallback,
                mHeadsUpManager);
        gutsManager.setUpWithPresenter(mPresenter, entryManager, mListContainer,
                mCheckSaveListener, mOnClickListener);
        notificationLogger.setUpWithEntryManager(entryManager, mListContainer);
        mediaManager.setUpWithPresenter(mPresenter, entryManager);
        remoteInputManager.setUpWithPresenter(mPresenter, entryManager, mRemoteInputManagerCallback,
                mDelegate);
        lockscreenUserManager.setUpWithPresenter(mPresenter, entryManager);
        viewHierarchyManager.setUpWithPresenter(mPresenter, entryManager, mListContainer);
        notificationListener.setUpWithPresenter(mPresenter, entryManager);

        assertFalse(mDependency.hasInstantiatedDependency(StatusBarWindowManager.class));
    }
}
