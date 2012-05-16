/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.example.android.rs.balls;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings.System;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ListView;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class Balls extends Activity implements SensorEventListener {
    //EventListener mListener = new EventListener();

    private static final String LOG_TAG = "libRS_jni";
    private static final boolean DEBUG  = false;
    private static final boolean LOG_ENABLED = false;

    private BallsView mView;
    private SensorManager mSensorManager;

    // get the current looper (from your Activity UI thread for instance


    public void onSensorChanged(SensorEvent event) {
        //android.util.Log.d("rs", "sensor: " + event.sensor + ", x: " + event.values[0] + ", y: " + event.values[1] + ", z: " + event.values[2]);
        synchronized (this) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                if(mView != null) {
                    mView.setAccel(event.values[0], event.values[1], event.values[2]);
                }
            }
        }
    }

    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        // Create our Preview view and set it as the content of our
        // Activity
        mView = new BallsView(this);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        mSensorManager.registerListener(this,
                                        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                                        SensorManager.SENSOR_DELAY_FASTEST);

        // Ideally a game should implement onResume() and onPause()
        // to take appropriate action when the activity looses focus
        super.onResume();
        mView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mView.pause();
        onStop();
    }

    @Override
    protected void onStop() {
        mSensorManager.unregisterListener(this);
        super.onStop();
    }

    static void log(String message) {
        if (LOG_ENABLED) {
            Log.v(LOG_TAG, message);
        }
    }


}

