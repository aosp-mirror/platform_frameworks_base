/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.doze;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.util.AsyncSensorManager;

/**
 * Controller responsible for waking up or making the device sleep based on ambient sensors.
 */
public class LockScreenWakeUpController implements StatusBarStateController.StateListener,
        SensorManagerPlugin.SensorEventListener {

    private static final String TAG = LockScreenWakeUpController.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final AsyncSensorManager mAsyncSensorManager;
    private final SensorManagerPlugin.Sensor mSensor;
    private final AmbientDisplayConfiguration mAmbientConfiguration;
    private final PowerManager mPowerManager;
    private final DozeHost mDozeHost;
    private final Handler mHandler;
    private boolean mRegistered;
    private boolean mDozing;

    public LockScreenWakeUpController(Context context, DozeHost dozeHost) {
        this(Dependency.get(AsyncSensorManager.class),
                new SensorManagerPlugin.Sensor(SensorManagerPlugin.Sensor.TYPE_WAKE_LOCK_SCREEN),
                new AmbientDisplayConfiguration(context),
                context.getSystemService(PowerManager.class),
                dozeHost, Dependency.get(StatusBarStateController.class), new Handler());
    }

    @VisibleForTesting
    public LockScreenWakeUpController(AsyncSensorManager asyncSensorManager,
            SensorManagerPlugin.Sensor sensor, AmbientDisplayConfiguration ambientConfiguration,
            PowerManager powerManager, DozeHost dozeHost,
            StatusBarStateController statusBarStateController, Handler handler) {
        mAsyncSensorManager = asyncSensorManager;
        mSensor = sensor;
        mAmbientConfiguration = ambientConfiguration;
        mPowerManager = powerManager;
        mDozeHost = dozeHost;
        mHandler = handler;
        statusBarStateController.addCallback(this);
    }

    @Override
    public void onStateChanged(int newState) {
        boolean isLockScreen = newState == StatusBarState.KEYGUARD
                || newState == StatusBarState.SHADE_LOCKED;

        if (!mAmbientConfiguration.wakeLockScreenGestureEnabled(UserHandle.USER_CURRENT)) {
            if (mRegistered) {
                mAsyncSensorManager.unregisterPluginListener(mSensor, this);
                mRegistered = false;
            }
            return;
        }

        if (isLockScreen && !mRegistered) {
            mAsyncSensorManager.registerPluginListener(mSensor, this);
            mRegistered = true;
        } else if (!isLockScreen && mRegistered) {
            mAsyncSensorManager.unregisterPluginListener(mSensor, this);
            mRegistered = false;
        }
    }

    @Override
    public void onDozingChanged(boolean isDozing) {
        mDozing = isDozing;
    }

    @Override
    public void onSensorChanged(SensorManagerPlugin.SensorEvent event) {
        mHandler.post(()-> {
            float[] rawValues = event.getValues();
            boolean wakeEvent = rawValues != null && rawValues.length > 0 && rawValues[0] != 0;

            DozeLog.traceLockScreenWakeUp(wakeEvent);
            if (wakeEvent && mDozing) {
                if (DEBUG) Log.d(TAG, "Wake up.");
                mPowerManager.wakeUp(SystemClock.uptimeMillis(), "com.android.systemui:NODOZE");
            } else if (!wakeEvent && !mDozing) {
                if (DEBUG) Log.d(TAG, "Nap time.");
                mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                        PowerManager.GO_TO_SLEEP_REASON_SLEEP_BUTTON, 0);
            } else if (DEBUG) {
                Log.d(TAG, "Skip sensor event. Wake? " + wakeEvent + " dozing: " + mDozing);
            }
        });
    }
}
