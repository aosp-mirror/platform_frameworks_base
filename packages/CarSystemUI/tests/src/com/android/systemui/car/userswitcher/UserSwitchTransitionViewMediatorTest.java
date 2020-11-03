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

package com.android.systemui.car.userswitcher;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.user.CarUserManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class UserSwitchTransitionViewMediatorTest extends SysuiTestCase {
    private static final int TEST_USER = 100;

    private UserSwitchTransitionViewMediator mUserSwitchTransitionViewMediator;
    @Mock
    private CarServiceProvider mCarServiceProvider;
    @Mock
    private CarDeviceProvisionedController mCarDeviceProvisionedController;
    @Mock
    private UserSwitchTransitionViewController mUserSwitchTransitionViewController;
    @Mock
    private CarUserManager.UserLifecycleEvent mUserLifecycleEvent;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mUserSwitchTransitionViewMediator = new UserSwitchTransitionViewMediator(
                mCarServiceProvider, mCarDeviceProvisionedController,
                mUserSwitchTransitionViewController);
        when(mCarDeviceProvisionedController.getCurrentUser()).thenReturn(TEST_USER);
    }

    @Test
    public void onUserLifecycleEvent_userStarting_isCurrentUser_callsHandleShow() {
        when(mUserLifecycleEvent.getEventType()).thenReturn(
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STARTING);
        when(mUserLifecycleEvent.getUserId()).thenReturn(TEST_USER);

        mUserSwitchTransitionViewMediator.handleUserLifecycleEvent(mUserLifecycleEvent);

        verify(mUserSwitchTransitionViewController).handleShow(TEST_USER);
    }

    @Test
    public void onUserLifecycleEvent_userStarting_isNotCurrentUser_doesNotCallHandleShow() {
        when(mUserLifecycleEvent.getEventType()).thenReturn(
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_STARTING);
        when(mUserLifecycleEvent.getUserId()).thenReturn(TEST_USER);
        when(mCarDeviceProvisionedController.getCurrentUser()).thenReturn(TEST_USER + 1);

        mUserSwitchTransitionViewMediator.handleUserLifecycleEvent(mUserLifecycleEvent);

        verify(mUserSwitchTransitionViewController, never()).handleShow(TEST_USER);
    }

    @Test
    public void onUserLifecycleEvent_userSwitching_callsHandleHide() {
        when(mUserLifecycleEvent.getEventType()).thenReturn(
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING);
        mUserSwitchTransitionViewMediator.handleUserLifecycleEvent(mUserLifecycleEvent);

        verify(mUserSwitchTransitionViewController).handleHide();
    }
}
