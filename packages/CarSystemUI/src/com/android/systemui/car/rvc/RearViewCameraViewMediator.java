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

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.Slog;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.window.OverlayViewMediator;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * View mediator for the rear view camera (RVC), which monitors the gear changes and shows
 * the RVC when the gear position is R and otherwise it hides the RVC.
 */
@SysUISingleton
public class RearViewCameraViewMediator implements OverlayViewMediator {
    private static final String TAG = "RearViewCameraView";
    private static final boolean DBG = false;

    private final RearViewCameraViewController mRearViewCameraViewController;
    private final CarServiceProvider mCarServiceProvider;
    private final BroadcastDispatcher mBroadcastDispatcher;

    private CarPropertyManager mCarPropertyManager;
    // TODO(b/170792252): Replace the following with the callback from CarEvsManager if it's ready.
    private final CarPropertyEventCallback mPropertyEventCallback = new CarPropertyEventCallback() {
        @Override
        public void onChangeEvent(CarPropertyValue value) {
            if (DBG) Slog.d(TAG, "onChangeEvent value=" + value);
            if (value.getPropertyId() != VehiclePropertyIds.GEAR_SELECTION) {
                Slog.w(TAG, "Got the event for non-registered property: " + value.getPropertyId());
                return;
            }
            if ((Integer) value.getValue() == VehicleGear.GEAR_REVERSE) {
                mRearViewCameraViewController.start();
            } else {
                mRearViewCameraViewController.stop();
            }
        }
        @Override
        public void onErrorEvent(int propId, int zone) {
            Slog.e(TAG, "onErrorEvent propId=" + propId + ", zone=" + zone);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Slog.d(TAG, "onReceive: " + intent);
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                    && mRearViewCameraViewController.isShown()) {
                mRearViewCameraViewController.stop();
            }
        }
    };

    @Inject
    public RearViewCameraViewMediator(
            RearViewCameraViewController rearViewCameraViewController,
            CarServiceProvider carServiceProvider,
            BroadcastDispatcher broadcastDispatcher) {
        if (DBG) Slog.d(TAG, "RearViewCameraViewMediator:init");
        mRearViewCameraViewController = rearViewCameraViewController;
        mCarServiceProvider = carServiceProvider;
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    public void registerListeners() {
        if (DBG) Slog.d(TAG, "RearViewCameraViewMediator:registerListeners");
        if (!mRearViewCameraViewController.isEnabled()) {
            Slog.i(TAG, "RearViewCameraViewController isn't enabled");
            return;
        }

        mCarServiceProvider.addListener(car -> {
            mCarPropertyManager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (mCarPropertyManager == null) {
                Slog.e(TAG, "Unable to get CarPropertyManager");
                return;
            }
            if (DBG) Slog.d(TAG, "Registering mPropertyEventCallback.");
            mCarPropertyManager.registerCallback(mPropertyEventCallback,
                    VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_UI);
        });
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), /* executor= */ null,
                UserHandle.ALL);
    }

    @Override
    public void setupOverlayContentViewControllers() {}
}
