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

package com.android.systemui.car.rvc;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.testing.TestableLooper;
import android.util.Log;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarServiceProvider.CarServiceOnConnectedListener;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@CarSystemUiTest
@RunWith(MockitoJUnitRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class RearViewCameraViewMediatorTest extends SysuiTestCase {
    private static final String TAG = RearViewCameraViewMediatorTest.class.getSimpleName();

    private RearViewCameraViewMediator mRearViewCameraViewMediator;

    @Mock
    private CarServiceProvider mCarServiceProvider;
    @Mock
    private Car mCar;
    @Mock
    private CarPropertyManager mCarPropertyManager;
    @Captor
    private ArgumentCaptor<CarPropertyEventCallback> mCarPropertyEventCallbackCaptor;

    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;
    @Captor
    private ArgumentCaptor<IntentFilter> mIntentFilterCaptor;

    @Mock
    private RearViewCameraViewController mRearViewCameraViewController;

    @Before
    public void setUp() throws Exception {
        mRearViewCameraViewMediator = new RearViewCameraViewMediator(
                mRearViewCameraViewController, mCarServiceProvider, mBroadcastDispatcher);
    }

    public void setUpListener() {
        doAnswer(invocation -> {
            CarServiceOnConnectedListener listener = invocation.getArgument(0);
            listener.onConnected(mCar);
            return null;
        }).when(mCarServiceProvider).addListener(any(CarServiceOnConnectedListener.class));
        when(mCar.getCarManager(Car.PROPERTY_SERVICE)).thenReturn(mCarPropertyManager);
        when(mRearViewCameraViewController.isEnabled()).thenReturn(true);

        mRearViewCameraViewMediator.registerListeners();

        verify(mCarPropertyManager).registerCallback(mCarPropertyEventCallbackCaptor.capture(),
                eq(VehiclePropertyIds.GEAR_SELECTION), anyFloat());
        verify(mBroadcastDispatcher).registerReceiver(mBroadcastReceiverCaptor.capture(),
                mIntentFilterCaptor.capture(), any(), any());
        assertThat(mIntentFilterCaptor.getValue().getAction(0)).isEqualTo(
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
    }

    @Test
    public void testDoesnNotRegisterListenersWhenRearViewCameraViewControllerIsDisabled() {
        when(mRearViewCameraViewController.isEnabled()).thenReturn(false);

        mRearViewCameraViewMediator.registerListeners();

        verify(mCarPropertyManager, never()).registerCallback(any(), anyInt(), anyFloat());
        verify(mBroadcastDispatcher, never()).registerReceiver(any(), any(), any());
    }

    @Test
    public void testGearReverseStartsRearViewCamera() {
        setUpListener();

        CarPropertyValue<Integer> gearReverse = new CarPropertyValue(
                VehiclePropertyIds.GEAR_SELECTION, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                VehicleGear.GEAR_REVERSE);
        mCarPropertyEventCallbackCaptor.getValue().onChangeEvent(gearReverse);

        verify(mRearViewCameraViewController, times(1)).start();
    }

    @Test
    public void testGearNonReverseStopsRearViewCamera() {
        setUpListener();

        int[] nonReverseVehicleGears = new int[]{
                VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_PARK, VehicleGear.GEAR_DRIVE,
                VehicleGear.GEAR_FIRST
        };
        for (int i = 0; i < nonReverseVehicleGears.length; ++i) {
            Log.i(TAG, "testGearNonReverseStopsRearViewCamera: gear=" + nonReverseVehicleGears[i]);
            CarPropertyValue<Integer> propertyGear = new CarPropertyValue(
                    VehiclePropertyIds.GEAR_SELECTION, VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                    nonReverseVehicleGears[i]);
            mCarPropertyEventCallbackCaptor.getValue().onChangeEvent(propertyGear);

            verify(mRearViewCameraViewController, times(i + 1)).stop();
        }
    }

    @Test
    public void testBroadcastIntentStopsRearViewCamera() {
        setUpListener();
        when(mRearViewCameraViewController.isShown()).thenReturn(true);

        Intent randomIntent = new Intent(Intent.ACTION_MAIN);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, randomIntent);

        verify(mRearViewCameraViewController, never()).stop();

        Intent actionCloseSystemDialogs = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mBroadcastReceiverCaptor.getValue().onReceive(mContext, actionCloseSystemDialogs);

        verify(mRearViewCameraViewController, times(1)).stop();
    }
}
