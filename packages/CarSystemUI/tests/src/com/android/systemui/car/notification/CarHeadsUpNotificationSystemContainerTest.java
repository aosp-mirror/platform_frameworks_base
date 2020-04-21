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

package com.android.systemui.car.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CarHeadsUpNotificationSystemContainerTest extends SysuiTestCase {
    private CarHeadsUpNotificationSystemContainer mDefaultController;
    private CarHeadsUpNotificationSystemContainer mOverrideEnabledController;
    @Mock
    private CarDeviceProvisionedController mCarDeviceProvisionedController;
    @Mock
    private NotificationPanelViewController mNotificationPanelViewController;
    @Mock
    private WindowManager mWindowManager;

    @Mock
    private View mNotificationView;
    @Mock
    private View mNotificationView2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(false);
        when(mCarDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        when(mCarDeviceProvisionedController.isCurrentUserSetupInProgress()).thenReturn(false);

        TestableResources testableResources = mContext.getOrCreateTestableResources();

        testableResources.addOverride(
                R.bool.config_enableHeadsUpNotificationWhenNotificationShadeOpen, false);

        mDefaultController = new CarHeadsUpNotificationSystemContainer(mContext,
                testableResources.getResources(), mCarDeviceProvisionedController, mWindowManager,
                () -> mNotificationPanelViewController);

        testableResources.addOverride(
                R.bool.config_enableHeadsUpNotificationWhenNotificationShadeOpen, true);

        mOverrideEnabledController = new CarHeadsUpNotificationSystemContainer(mContext,
                testableResources.getResources(), mCarDeviceProvisionedController, mWindowManager,
                () -> mNotificationPanelViewController);
    }

    @Test
    public void testDisplayNotification_firstNotification_isVisible() {
        mDefaultController.displayNotification(mNotificationView);
        assertThat(mDefaultController.isVisible()).isTrue();
    }

    @Test
    public void testRemoveNotification_lastNotification_isInvisible() {
        mDefaultController.displayNotification(mNotificationView);
        mDefaultController.removeNotification(mNotificationView);
        assertThat(mDefaultController.isVisible()).isFalse();
    }

    @Test
    public void testRemoveNotification_nonLastNotification_isVisible() {
        mDefaultController.displayNotification(mNotificationView);
        mDefaultController.displayNotification(mNotificationView2);
        mDefaultController.removeNotification(mNotificationView);
        assertThat(mDefaultController.isVisible()).isTrue();
    }

    @Test
    public void testDisplayNotification_userSetupInProgress_isInvisible() {
        when(mCarDeviceProvisionedController.isCurrentUserSetupInProgress()).thenReturn(true);
        mDefaultController.displayNotification(mNotificationView);
        assertThat(mDefaultController.isVisible()).isFalse();

    }

    @Test
    public void testDisplayNotification_userSetupIncomplete_isInvisible() {
        when(mCarDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);
        mDefaultController.displayNotification(mNotificationView);
        assertThat(mDefaultController.isVisible()).isFalse();
    }

    @Test
    public void testDisplayNotification_notificationPanelExpanded_isInvisible() {
        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(true);
        mDefaultController.displayNotification(mNotificationView);
        assertThat(mDefaultController.isVisible()).isFalse();
    }

    @Test
    public void testDisplayNotification_notificationPanelExpandedEnabledHUNWhenOpen_isVisible() {
        when(mNotificationPanelViewController.isPanelExpanded()).thenReturn(true);
        mOverrideEnabledController.displayNotification(mNotificationView);
        assertThat(mOverrideEnabledController.isVisible()).isTrue();
    }
}
