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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationViewHierarchyManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.UnlockMethodCache;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import dagger.Lazy;


@SmallTest
@org.junit.runner.RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DynamicPrivacyControllerTest extends SysuiTestCase {

    private DynamicPrivacyController mDynamicPrivacyController;
    private UnlockMethodCache mCache = mock(UnlockMethodCache.class);
    private NotificationLockscreenUserManager mLockScreenUserManager
            = mock(NotificationLockscreenUserManager.class);
    private DynamicPrivacyController.Listener mListener
            = mock(DynamicPrivacyController.Listener.class);
    private KeyguardMonitor mKeyguardMonitor = mock(KeyguardMonitor.class);

    @Before
    public void setUp() throws Exception {
        when(mCache.canSkipBouncer()).thenReturn(false);
        when(mKeyguardMonitor.isShowing()).thenReturn(true);
        mDynamicPrivacyController = new DynamicPrivacyController(
                mLockScreenUserManager, mKeyguardMonitor, mCache,
                mock(StatusBarStateController.class));
        mDynamicPrivacyController.setStatusBarKeyguardViewManager(
                mock(StatusBarKeyguardViewManager.class));
        mDynamicPrivacyController.addListener(mListener);
    }

    @Test
    public void testDynamicFalseWhenCannotSkipBouncer() {
        enableDynamicPrivacy();
        when(mCache.canSkipBouncer()).thenReturn(false);
        Assert.assertFalse("can't skip bouncer but is dynamically unlocked",
                mDynamicPrivacyController.isDynamicallyUnlocked());
    }

    @Test
    public void testDynamicTrueWhenCanSkipBouncer() {
        enableDynamicPrivacy();
        when(mCache.canSkipBouncer()).thenReturn(true);
        Assert.assertTrue("Isn't dynamically unlocked even though we can skip bouncer",
                mDynamicPrivacyController.isDynamicallyUnlocked());
    }

    @Test
    public void testNotifiedWhenEnabled() {
        when(mCache.canSkipBouncer()).thenReturn(true);
        enableDynamicPrivacy();
        mDynamicPrivacyController.onUnlockMethodStateChanged();
        verify(mListener).onDynamicPrivacyChanged();
    }

    private void enableDynamicPrivacy() {
        when(mLockScreenUserManager.shouldHideNotifications(any())).thenReturn(
                false);
    }

    @Test
    public void testNotNotifiedWithoutNotifications() {
        when(mCache.canSkipBouncer()).thenReturn(true);
        when(mLockScreenUserManager.shouldHideNotifications(anyInt())).thenReturn(
                true);
        mDynamicPrivacyController.onUnlockMethodStateChanged();
        verifyNoMoreInteractions(mListener);
    }
}