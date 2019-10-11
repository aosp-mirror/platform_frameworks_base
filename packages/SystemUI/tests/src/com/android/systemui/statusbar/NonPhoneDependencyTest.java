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

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import org.junit.Before;
import org.junit.Ignore;
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
@Ignore("b/118400112")
public class NonPhoneDependencyTest extends SysuiTestCase {
    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationListContainer mListContainer;
    @Mock
    private NotificationEntryListener mEntryListener;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private RemoteInputController.Delegate mDelegate;
    @Mock private NotificationRemoteInputManager.Callback mRemoteInputManagerCallback;
    @Mock private CheckSaveListener mCheckSaveListener;
    @Mock private OnSettingsClickListener mOnSettingsClickListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
               new Handler(TestableLooper.get(this).getLooper()));
    }

    @Test
    @Ignore("b/118400112")
    public void testNotificationManagementCodeHasNoDependencyOnStatusBarWindowManager() {
        mDependency.injectMockDependency(ShadeController.class);
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
        Dependency.get(InitController.class).executePostInitTasks();
        entryManager.setUpWithPresenter(mPresenter, mListContainer, mHeadsUpManager);
        entryManager.addNotificationEntryListener(mEntryListener);
        gutsManager.setUpWithPresenter(mPresenter, mListContainer,
                mCheckSaveListener, mOnSettingsClickListener);
        notificationLogger.setUpWithContainer(mListContainer);
        mediaManager.setUpWithPresenter(mPresenter);
        remoteInputManager.setUpWithCallback(mRemoteInputManagerCallback,
                mDelegate);
        lockscreenUserManager.setUpWithPresenter(mPresenter);
        viewHierarchyManager.setUpWithPresenter(mPresenter, mListContainer);

        TestableLooper.get(this).processAllMessages();
        assertFalse(mDependency.hasInstantiatedDependency(StatusBarWindowController.class));
    }
}
