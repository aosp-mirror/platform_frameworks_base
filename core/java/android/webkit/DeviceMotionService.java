/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Message;
import android.webkit.DeviceMotionAndOrientationManager;
import java.lang.Runnable;
import java.util.List;


final class DeviceMotionService implements SensorEventListener {
    private DeviceMotionAndOrientationManager mManager;
    private boolean mIsRunning;
    private Handler mHandler;
    private SensorManager mSensorManager;
    private Context mContext;
    private boolean mHaveSentErrorEvent;
    private Runnable mUpdateRunnable;
    private float mLastAcceleration[];

    private static final int INTERVAL_MILLIS = 100;

    public DeviceMotionService(DeviceMotionAndOrientationManager manager, Context context) {
        mManager = manager;
        assert(mManager != null);
        mContext = context;
        assert(mContext != null);
     }

    public void start() {
        mIsRunning = true;
        registerForSensor();
    }

    public void stop() {
        mIsRunning = false;
        stopSendingUpdates();
        unregisterFromSensor();
    }

    public void suspend() {
        if (mIsRunning) {
            stopSendingUpdates();
            unregisterFromSensor();
        }
    }

    public void resume() {
        if (mIsRunning) {
            registerForSensor();
        }
    }

    private void sendErrorEvent() {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        // The spec requires that each listener receives the error event only once.
        if (mHaveSentErrorEvent)
            return;
        mHaveSentErrorEvent = true;
        createHandler();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
                if (mIsRunning) {
                    // The special case of all nulls is used to signify a failure to get data.
                    mManager.onMotionChange(null, null, null, 0.0);
                }
            }
        });
    }

    private void createHandler() {
        if (mHandler != null) {
            return;
        }

        mHandler = new Handler();
        mUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                assert mIsRunning;
                mManager.onMotionChange(new Double(mLastAcceleration[0]),
                        new Double(mLastAcceleration[1]), new Double(mLastAcceleration[2]),
                        INTERVAL_MILLIS);
                mHandler.postDelayed(mUpdateRunnable, INTERVAL_MILLIS);
                // Now that we have successfully sent some data, reset whether we've sent an error.
                mHaveSentErrorEvent = false;
            }
        };
    }

    private void startSendingUpdates() {
        createHandler();
        mUpdateRunnable.run();
    }

    private void stopSendingUpdates() {
        mHandler.removeCallbacks(mUpdateRunnable);
        mLastAcceleration = null;
    }

    private void registerForSensor() {
        if (!registerForAccelerometerSensor()) {
            sendErrorEvent();
        }
    }

    private SensorManager getSensorManager() {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        if (mSensorManager == null) {
            mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        }
        return mSensorManager;
    }

    private boolean registerForAccelerometerSensor() {
        List<Sensor> sensors = getSensorManager().getSensorList(Sensor.TYPE_ACCELEROMETER);
        if (sensors.isEmpty()) {
            return false;
        }
        createHandler();
        // TODO: Consider handling multiple sensors.
        return getSensorManager().registerListener(
                this, sensors.get(0), SensorManager.SENSOR_DELAY_UI, mHandler);
    }

    private void unregisterFromSensor() {
        getSensorManager().unregisterListener(this);
    }

    /**
     * SensorEventListener implementation.
     * Callbacks happen on the thread on which we registered - the WebCore thread.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        assert(event.values.length == 3);
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        assert(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER);

        // We may get callbacks after the call to getSensorManager().unregisterListener() returns.
        if (!mIsRunning) {
            return;
        }

        boolean firstData = mLastAcceleration == null;
        mLastAcceleration = event.values;
        if (firstData) {
            startSendingUpdates();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
    }
}
