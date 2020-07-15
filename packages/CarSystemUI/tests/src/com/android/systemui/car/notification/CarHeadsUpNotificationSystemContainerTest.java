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

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.car.window.OverlayViewGlobalStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CarHeadsUpNotificationSystemContainerTest extends SysuiTestCase {
    private CarHeadsUpNotificationSystemContainer mCarHeadsUpNotificationSystemContainer;
    @Mock
    private CarDeviceProvisionedController mCarDeviceProvisionedController;
    @Mock
    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;
    @Mock
    private WindowManager mWindowManager;

    @Mock
    private View mNotificationView;
    @Mock
    private View mNotificationView2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */this);

        when(mOverlayViewGlobalStateController.shouldShowHUN()).thenReturn(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);

        TestableResources testableResources = mContext.getOrCreateTestableResources();

        mCarHeadsUpNotificationSystemContainer = new CarHeadsUpNotificationSystemContainer(mContext,
                testableResources.getResources(), mCarDeviceProvisionedController, mWindowManager,
                mOverlayViewGlobalStateController);
    }

    @Test
    public void testDisplayNotification_firstNotification_isVisible() {
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isTrue();
    }

    @Test
    public void testRemoveNotification_lastNotification_isInvisible() {
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        mCarHeadsUpNotificationSystemContainer.removeNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isFalse();
    }

    @Test
    public void testRemoveNotification_nonLastNotification_isVisible() {
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView2);
        mCarHeadsUpNotificationSystemContainer.removeNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isTrue();
    }

    @Test
    public void testDisplayNotification_userFullySetupTrue_isInvisible() {
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isTrue();

    }

    @Test
    public void testDisplayNotification_userFullySetupFalse_isInvisible() {
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(false);
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isFalse();
    }

    @Test
    public void testDisplayNotification_overlayWindowStateShouldShowHUNFalse_isInvisible() {
        when(mOverlayViewGlobalStateController.shouldShowHUN()).thenReturn(false);
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isFalse();
    }

    @Test
    public void testDisplayNotification_overlayWindowStateShouldShowHUNTrue_isVisible() {
        mCarHeadsUpNotificationSystemContainer.displayNotification(mNotificationView);
        assertThat(mCarHeadsUpNotificationSystemContainer.isVisible()).isTrue();
    }
}
