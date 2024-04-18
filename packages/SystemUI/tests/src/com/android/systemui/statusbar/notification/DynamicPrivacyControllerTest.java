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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

@SmallTest
@org.junit.runner.RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DynamicPrivacyControllerTest extends SysuiTestCase {

    private DynamicPrivacyController mDynamicPrivacyController;
    private NotificationLockscreenUserManager mLockScreenUserManager
            = mock(NotificationLockscreenUserManager.class);
    private DynamicPrivacyController.Listener mListener
            = mock(DynamicPrivacyController.Listener.class);
    private KeyguardStateController mKeyguardStateController = mock(KeyguardStateController.class);

    @Before
    public void setUp() throws Exception {
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        mDynamicPrivacyController = new DynamicPrivacyController(
                mLockScreenUserManager, mKeyguardStateController,
                mock(StatusBarStateController.class));
        mDynamicPrivacyController.addListener(mListener);
        // Disable dynamic privacy by default
        allowNotificationsInPublic(false);
    }

    @Test
    public void testDynamicFalseWhenCannotSkipBouncer() {
        enableDynamicPrivacy();
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        assertFalse("can't skip bouncer but is dynamically unlocked",
                mDynamicPrivacyController.isDynamicallyUnlocked());
    }

    @Test
    public void testDynamicTrueWhenCanSkipBouncer() {
        enableDynamicPrivacy();
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        Assert.assertTrue("Isn't dynamically unlocked even though we can skip bouncer",
                mDynamicPrivacyController.isDynamicallyUnlocked());
    }

    @Test
    public void testNotifiedWhenEnabled() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        enableDynamicPrivacy();
        mDynamicPrivacyController.onUnlockedChanged();
        verify(mListener).onDynamicPrivacyChanged();
    }

    @Test
    public void testNotifiedWhenKeyguardFadingAwayChanges() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(false);
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(false);
        enableDynamicPrivacy();

        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(true);
        mDynamicPrivacyController.onKeyguardFadingAwayChanged();
        verify(mListener).onDynamicPrivacyChanged();
        reset(mListener);

        when(mKeyguardStateController.isKeyguardFadingAway()).thenReturn(false);
        mDynamicPrivacyController.onUnlockedChanged();
        verify(mListener).onDynamicPrivacyChanged();
    }

    @Test
    public void dynamicPrivacyOnlyWhenHidingPrivate() {
        // Verify that when hiding notifications, this isn't enabled
        allowNotificationsInPublic(false);
        assertFalse("Dynamic privacy shouldn't be enabled when hiding notifications",
                mDynamicPrivacyController.isDynamicPrivacyEnabled());
        allowNotificationsInPublic(true);
        assertTrue("Should be enabled whenever notifications are visible",
                mDynamicPrivacyController.isDynamicPrivacyEnabled());
    }

    private void enableDynamicPrivacy() {
        allowNotificationsInPublic(true);
    }

    private void allowNotificationsInPublic(boolean allow) {
        when(mLockScreenUserManager.userAllowsNotificationsInPublic(anyInt())).thenReturn(allow);
    }

    @Test
    public void testNotNotifiedWithoutNotifications() {
        when(mKeyguardStateController.canDismissLockScreen()).thenReturn(true);
        mDynamicPrivacyController.onUnlockedChanged();
        verifyNoMoreInteractions(mListener);
    }
}