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

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Verifies that particular sets of dependencies don't have dependencies on others. For example,
 * code managing notifications shouldn't directly depend on CentralSurfaces, since there are
 * platforms which want to manage notifications, but don't use CentralSurfaces.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NonPhoneDependencyTest extends SysuiTestCase {
    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationListContainer mListContainer;
    @Mock private RemoteInputController.Delegate mDelegate;
    @Mock private NotificationRemoteInputManager.Callback mRemoteInputManagerCallback;
    @Mock private OnSettingsClickListener mOnSettingsClickListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(KeyguardUpdateMonitor.class);
        mDependency.injectTestDependency(Dependency.MAIN_HANDLER,
               new Handler(TestableLooper.get(this).getLooper()));
    }

    @Ignore("Causes binder calls which fail")
    @Test
    public void testNotificationManagementCodeHasNoDependencyOnStatusBarWindowManager() {
        mDependency.injectMockDependency(ShadeController.class);
        NotificationGutsManager gutsManager = Dependency.get(NotificationGutsManager.class);
        NotificationLogger notificationLogger = Dependency.get(NotificationLogger.class);
        NotificationMediaManager mediaManager = Dependency.get(NotificationMediaManager.class);
        NotificationRemoteInputManager remoteInputManager =
                Dependency.get(NotificationRemoteInputManager.class);
        NotificationLockscreenUserManager lockscreenUserManager =
                Dependency.get(NotificationLockscreenUserManager.class);
        gutsManager.setUpWithPresenter(mPresenter, mListContainer,
                mOnSettingsClickListener);
        notificationLogger.setUpWithContainer(mListContainer);
        mediaManager.setUpWithPresenter(mPresenter);
        remoteInputManager.setUpWithCallback(mRemoteInputManagerCallback,
                mDelegate);
        lockscreenUserManager.setUpWithPresenter(mPresenter);

        TestableLooper.get(this).processAllMessages();
        assertFalse(mDependency.hasInstantiatedDependency(NotificationShadeWindowController.class));
    }
}
