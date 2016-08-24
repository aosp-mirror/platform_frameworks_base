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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

public class SensorNotificationService extends SystemService implements SensorEventListener {
    //TODO: set DBG to false or remove Slog before release
    private static final boolean DBG = true;
    private static final String TAG = "SensorNotificationService";
    private Context mContext;

    private SensorManager mSensorManager;
    private Sensor mMetaSensor;

    public SensorNotificationService(Context context) {
        super(context);
        mContext = context;
    }

    public void onStart() {
        LocalServices.addService(SensorNotificationService.class, this);
    }

    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            // start
            mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
            mMetaSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_DYNAMIC_SENSOR_META);
            if (mMetaSensor == null) {
                if (DBG) Slog.d(TAG, "Cannot obtain dynamic meta sensor, not supported.");
            } else {
                mSensorManager.registerListener(this, mMetaSensor,
                        SensorManager.SENSOR_DELAY_FASTEST);
            }
        }
    }

    private void broadcastDynamicSensorChanged() {
        Intent i = new Intent(Intent.ACTION_DYNAMIC_SENSOR_CHANGED);
        i.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY); // avoid waking up manifest receivers
        mContext.sendBroadcastAsUser(i, UserHandle.ALL);
        if (DBG) Slog.d(TAG, "DYNS sent dynamic sensor broadcast");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == mMetaSensor) {
            broadcastDynamicSensorChanged();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}

