/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.volume.car;

import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.media.CarAudioManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.android.systemui.volume.VolumeDialogControllerImpl;

/**
 * A volume dialog controller for the automotive use case.
 *
 * {@link android.car.media.CarAudioManager} is the source of truth to get the stream volumes.
 * And volume changes should be sent to the car's audio module instead of the android's audio mixer.
 */
public class CarVolumeDialogController extends VolumeDialogControllerImpl {
    private static final String TAG = "CarVolumeDialogController";

    private final Car mCar;
    private CarAudioManager mCarAudioManager;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            try {
                mCarAudioManager = (CarAudioManager) mCar.getCarManager(Car.AUDIO_SERVICE);
                setVolumeController();
                CarVolumeDialogController.this.getState();
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car is not connected!", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.e(TAG, "Car service is disconnected");
        }
    };

    public CarVolumeDialogController(Context context) {
        super(context);
        mCar = Car.createCar(context, mConnection);
        mCar.connect();
    }

    @Override
    protected void setAudioManagerStreamVolume(int stream, int level, int flag) {
        if (mCarAudioManager == null) {
            Log.d(TAG, "Car audio manager is not initialized yet");
            return;
        }
        try {
            mCarAudioManager.setStreamVolume(stream, level, flag);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        }
    }

    @Override
    protected int getAudioManagerStreamVolume(int stream) {
        if(mCarAudioManager == null) {
            Log.d(TAG, "Car audio manager is not initialized yet");
            return 0;
        }

        try {
            return mCarAudioManager.getStreamVolume(stream);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
            return 0;
        }
    }

    @Override
    protected int getAudioManagerStreamMaxVolume(int stream) {
        if(mCarAudioManager == null) {
            Log.d(TAG, "Car audio manager is not initialized yet");
            return 0;
        }

        try {
            return mCarAudioManager.getStreamMaxVolume(stream);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
            return 0;
        }
    }

    @Override
    protected int getAudioManagerStreamMinVolume(int stream) {
        if(mCarAudioManager == null) {
            Log.d(TAG, "Car audio manager is not initialized yet");
            return 0;
        }

        try {
            return mCarAudioManager.getStreamMinVolume(stream);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
            return 0;
        }
    }

    @Override
    public void setVolumeController() {
        if (mCarAudioManager == null) {
            Log.d(TAG, "Car audio manager is not initialized yet");
            return;
        }
        try {
            mCarAudioManager.setVolumeController(mVolumeController);
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car is not connected", e);
        }
    }
}
