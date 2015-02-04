/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.policy;

import android.os.Handler;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;

import java.io.PrintWriter;

/**
 * Watches for wake gesture sensor events then invokes the listener.
 */
public abstract class WakeGestureListener {
    private static final String TAG = "WakeGestureListener";

    private final SensorManager mSensorManager;
    private final Handler mHandler;

    private final Object mLock = new Object();

    private boolean mTriggerRequested;
    private Sensor mSensor;

    public WakeGestureListener(Context context, Handler handler) {
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mHandler = handler;

        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_WAKE_GESTURE);
    }

    public abstract void onWakeUp();

    public boolean isSupported() {
        synchronized (mLock) {
            return mSensor != null;
        }
    }

    public void requestWakeUpTrigger() {
        synchronized (mLock) {
            if (mSensor != null && !mTriggerRequested) {
                mTriggerRequested = true;
                mSensorManager.requestTriggerSensor(mListener, mSensor);
            }
        }
    }

    public void cancelWakeUpTrigger() {
        synchronized (mLock) {
            if (mSensor != null && mTriggerRequested) {
                mTriggerRequested = false;
                mSensorManager.cancelTriggerSensor(mListener, mSensor);
            }
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.println(prefix + TAG);
            prefix += "  ";
            pw.println(prefix + "mTriggerRequested=" + mTriggerRequested);
            pw.println(prefix + "mSensor=" + mSensor);
        }
    }

    private final TriggerEventListener mListener = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            synchronized (mLock) {
                mTriggerRequested = false;
                mHandler.post(mWakeUpRunnable);
            }
        }
    };

    private final Runnable mWakeUpRunnable = new Runnable() {
        @Override
        public void run() {
            onWakeUp();
        }
    };
}
