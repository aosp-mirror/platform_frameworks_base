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

package com.android.systemui.car.statusbar;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.user.CarUserManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class UserNameViewControllerTest extends SysuiTestCase {

    private final UserInfo mUserInfo1 = new UserInfo(/* id= */ 0, "Test User Name", /* flags= */ 0);
    private final UserInfo mUserInfo2 = new UserInfo(/* id= */ 1, "Another User", /* flags= */ 0);
    private TextView mTextView;
    private UserNameViewController mUserNameViewController;

    @Mock
    private Car mCar;
    @Mock
    private CarUserManager mCarUserManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private CarDeviceProvisionedController mCarDeviceProvisionedController;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mUserManager.getUserInfo(mUserInfo1.id)).thenReturn(mUserInfo1);
        when(mUserManager.getUserInfo(mUserInfo2.id)).thenReturn(mUserInfo2);
        when(mCar.isConnected()).thenReturn(true);
        when(mCar.getCarManager(Car.CAR_USER_SERVICE)).thenReturn(mCarUserManager);

        CarServiceProvider carServiceProvider = new CarServiceProvider(mContext, mCar);
        mUserNameViewController = new UserNameViewController(getContext(), carServiceProvider,
                mUserManager, mBroadcastDispatcher, mCarDeviceProvisionedController);

        mTextView = new TextView(getContext());
        mTextView.setId(R.id.user_name_text);
    }

    @Test
    public void addUserNameViewToController_updatesUserNameView() {
        when(mCarDeviceProvisionedController.getCurrentUser()).thenReturn(mUserInfo1.id);

        mUserNameViewController.addUserNameView(mTextView);

        assertEquals(mTextView.getText(), mUserInfo1.name);
    }

    @Test
    public void addUserNameViewToController_withNoTextView_doesNotUpdate() {
        View nullView = new View(getContext());
        mUserNameViewController.addUserNameView(nullView);

        assertEquals(mTextView.getText(), "");
        verifyZeroInteractions(mCarDeviceProvisionedController);
        verifyZeroInteractions(mCarUserManager);
        verifyZeroInteractions(mUserManager);
    }

    @Test
    public void userLifecycleListener_onUserSwitchLifecycleEvent_updatesUserNameView() {
        ArgumentCaptor<CarUserManager.UserLifecycleListener> userLifecycleListenerArgumentCaptor =
                ArgumentCaptor.forClass(CarUserManager.UserLifecycleListener.class);
        when(mCarDeviceProvisionedController.getCurrentUser()).thenReturn(mUserInfo1.id);
        // Add the initial TextView, which registers the UserLifecycleListener
        mUserNameViewController.addUserNameView(mTextView);
        assertEquals(mTextView.getText(), mUserInfo1.name);
        verify(mCarUserManager).addListener(any(), userLifecycleListenerArgumentCaptor.capture());

        CarUserManager.UserLifecycleEvent event = new CarUserManager.UserLifecycleEvent(
                CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING, /* from= */ mUserInfo1.id,
                /* to= */ mUserInfo2.id);
        userLifecycleListenerArgumentCaptor.getValue().onEvent(event);

        assertEquals(mTextView.getText(), mUserInfo2.name);
    }

    @Test
    public void userInfoChangedBroadcast_withoutInitializingUserNameView_doesNothing() {
        getContext().sendBroadcast(new Intent(Intent.ACTION_USER_INFO_CHANGED));

        assertEquals(mTextView.getText(), "");
        verifyZeroInteractions(mCarDeviceProvisionedController);
    }

    @Test
    public void userInfoChangedBroadcast_withUserNameViewInitialized_updatesUserNameView() {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverArgumentCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        when(mCarDeviceProvisionedController.getCurrentUser()).thenReturn(mUserInfo1.id);
        mUserNameViewController.addUserNameView(mTextView);
        assertEquals(mTextView.getText(), mUserInfo1.name);
        verify(mBroadcastDispatcher).registerReceiver(broadcastReceiverArgumentCaptor.capture(),
                any(), any(), any());

        reset(mCarDeviceProvisionedController);
        when(mCarDeviceProvisionedController.getCurrentUser()).thenReturn(mUserInfo2.id);
        broadcastReceiverArgumentCaptor.getValue().onReceive(getContext(),
                new Intent(Intent.ACTION_USER_INFO_CHANGED));

        assertEquals(mTextView.getText(), mUserInfo2.name);
        verify(mCarDeviceProvisionedController).getCurrentUser();
    }
}
