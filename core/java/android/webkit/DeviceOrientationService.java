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
import android.webkit.DeviceMotionAndOrientationManager;
import java.lang.Runnable;
import java.util.List;


final class DeviceOrientationService implements SensorEventListener {
    // The gravity vector expressed in the body frame.
    private float[] mGravityVector;
    // The geomagnetic vector expressed in the body frame.
    private float[] mMagneticFieldVector;

    private DeviceMotionAndOrientationManager mManager;
    private boolean mIsRunning;
    private Handler mHandler;
    private SensorManager mSensorManager;
    private Context mContext;
    private Double mAlpha;
    private Double mBeta;
    private Double mGamma;
    private boolean mHaveSentErrorEvent;

    private static final double DELTA_DEGRESS = 1.0;

    public DeviceOrientationService(DeviceMotionAndOrientationManager manager, Context context) {
        mManager = manager;
        assert(mManager != null);
        mContext = context;
        assert(mContext != null);
     }

    public void start() {
        mIsRunning = true;
        registerForSensors();
    }

    public void stop() {
        mIsRunning = false;
        unregisterFromSensors();
    }

    public void suspend() {
        if (mIsRunning) {
            unregisterFromSensors();
        }
    }

    public void resume() {
        if (mIsRunning) {
            registerForSensors();
        }
    }

    private void sendErrorEvent() {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        // The spec requires that each listener receives the error event only once.
        if (mHaveSentErrorEvent)
            return;
        mHaveSentErrorEvent = true;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
                if (mIsRunning) {
                    // The special case of all nulls is used to signify a failure to get data.
                    mManager.onOrientationChange(null, null, null);
                }
            }
        });
    }

    private void registerForSensors() {
        if (mHandler == null) {
            mHandler = new Handler();
        }
        if (!registerForAccelerometerSensor() || !registerForMagneticFieldSensor()) {
            unregisterFromSensors();
            sendErrorEvent();
        }
    }

    private void getOrientationUsingGetRotationMatrix() {
        if (mGravityVector == null || mMagneticFieldVector == null) {
            return;
        }

        // Get the rotation matrix.
        // The rotation matrix that transforms from the body frame to the earth frame.
        float[] deviceRotationMatrix = new float[9];
        if (!SensorManager.getRotationMatrix(
                deviceRotationMatrix, null, mGravityVector, mMagneticFieldVector)) {
            return;
        }

        // Convert rotation matrix to rotation angles.
        // Assuming that the rotations are appied in the order listed at
        // http://developer.android.com/reference/android/hardware/SensorEvent.html#values
        // the rotations are applied about the same axes and in the same order as required by the
        // API. The only conversions are sign changes as follows.
        // The angles are in radians
        float[] rotationAngles = new float[3];
        SensorManager.getOrientation(deviceRotationMatrix, rotationAngles);
        double alpha = Math.toDegrees(-rotationAngles[0]);
        while (alpha < 0.0) { alpha += 360.0; } // [0, 360)
        double beta = Math.toDegrees(-rotationAngles[1]);
        while (beta < -180.0) { beta += 360.0; } // [-180, 180)
        double gamma = Math.toDegrees(rotationAngles[2]);
        while (gamma < -90.0) { gamma += 360.0; } // [-90, 90)

        maybeSendChange(alpha, beta, gamma);
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
        // TODO: Consider handling multiple sensors.
        return getSensorManager().registerListener(
                this, sensors.get(0), SensorManager.SENSOR_DELAY_FASTEST, mHandler);
    }

    private boolean registerForMagneticFieldSensor() {
        List<Sensor> sensors = getSensorManager().getSensorList(Sensor.TYPE_MAGNETIC_FIELD);
        if (sensors.isEmpty()) {
            return false;
        }
        // TODO: Consider handling multiple sensors.
        return getSensorManager().registerListener(
                this, sensors.get(0), SensorManager.SENSOR_DELAY_FASTEST, mHandler);
    }

    private void unregisterFromSensors() {
        getSensorManager().unregisterListener(this);
    }

    private void maybeSendChange(double alpha, double beta, double gamma) {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        if (mAlpha == null || mBeta == null || mGamma == null
                || Math.abs(alpha - mAlpha) > DELTA_DEGRESS
                || Math.abs(beta - mBeta) > DELTA_DEGRESS
                || Math.abs(gamma - mGamma) > DELTA_DEGRESS) {
            mAlpha = alpha;
            mBeta = beta;
            mGamma = gamma;
            mManager.onOrientationChange(mAlpha, mBeta, mGamma);
            // Now that we have successfully sent some data, reset whether we've sent an error.
            mHaveSentErrorEvent = false;
        }
    }

    /**
     * SensorEventListener implementation.
     * Callbacks happen on the thread on which we registered - the WebCore thread.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        assert(event.values.length == 3);
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());

        // We may get callbacks after the call to getSensorManager().unregisterListener() returns.
        if (!mIsRunning) {
            return;
        }

        switch (event.sensor.getType()) {
          case Sensor.TYPE_ACCELEROMETER:
            if (mGravityVector == null) {
                mGravityVector = new float[3];
            }
            mGravityVector[0] = event.values[0];
            mGravityVector[1] = event.values[1];
            mGravityVector[2] = event.values[2];
            getOrientationUsingGetRotationMatrix();
            break;
          case Sensor.TYPE_MAGNETIC_FIELD:
            if (mMagneticFieldVector == null) {
                mMagneticFieldVector = new float[3];
            }
            mMagneticFieldVector[0] = event.values[0];
            mMagneticFieldVector[1] = event.values[1];
            mMagneticFieldVector[2] = event.values[2];
            getOrientationUsingGetRotationMatrix();
            break;
          default:
            assert(false);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
    }
}
