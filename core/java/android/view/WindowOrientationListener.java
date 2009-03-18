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

package android.view;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Config;
import android.util.Log;

/**
 * A special helper class used by the WindowManager
 *  for receiving notifications from the SensorManager when
 * the orientation of the device has changed.
 * @hide
 */
public abstract class WindowOrientationListener {
    private static final String TAG = "WindowOrientationListener";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private SensorManager mSensorManager;
    private boolean mEnabled = false;
    private int mRate;
    private Sensor mSensor;
    private SensorEventListener mSensorEventListener;
    private int mSensorRotation = -1;

    /**
     * Creates a new WindowOrientationListener.
     * 
     * @param context for the WindowOrientationListener.
     */
    public WindowOrientationListener(Context context) {
        this(context, SensorManager.SENSOR_DELAY_NORMAL);
    }
    
    /**
     * Creates a new WindowOrientationListener.
     * 
     * @param context for the WindowOrientationListener.
     * @param rate at which sensor events are processed (see also
     * {@link android.hardware.SensorManager SensorManager}). Use the default
     * value of {@link android.hardware.SensorManager#SENSOR_DELAY_NORMAL 
     * SENSOR_DELAY_NORMAL} for simple screen orientation change detection.
     */
    public WindowOrientationListener(Context context, int rate) {
        mSensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        mRate = rate;
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (mSensor != null) {
            // Create listener only if sensors do exist
            mSensorEventListener = new SensorEventListenerImpl();
        }
    }

    /**
     * Enables the WindowOrientationListener so it will monitor the sensor and call
     * {@link #onOrientationChanged} when the device orientation changes.
     */
    public void enable() {
        if (mSensor == null) {
            Log.w(TAG, "Cannot detect sensors. Not enabled");
            return;
        }
        if (mEnabled == false) {
            if (localLOGV) Log.d(TAG, "WindowOrientationListener enabled");
            mSensorManager.registerListener(mSensorEventListener, mSensor, mRate);
            mEnabled = true;
        }
    }

    /**
     * Disables the WindowOrientationListener.
     */
    public void disable() {
        if (mSensor == null) {
            Log.w(TAG, "Cannot detect sensors. Invalid disable");
            return;
        }
        if (mEnabled == true) {
            if (localLOGV) Log.d(TAG, "WindowOrientationListener disabled");
            mSensorManager.unregisterListener(mSensorEventListener);
            mEnabled = false;
        }
    }

    class SensorEventListenerImpl implements SensorEventListener {
        private static final int _DATA_X = 0;
        private static final int _DATA_Y = 1;
        private static final int _DATA_Z = 2;
        // Angle around x-axis thats considered almost perfect vertical to hold
        // the device
        private static final int PIVOT = 30;
        // Angle around x-asis that's considered almost too vertical. Beyond
        // this angle will not result in any orientation changes. f phone faces uses,
        // the device is leaning backward.
        private static final int PIVOT_UPPER = 65;
        // Angle about x-axis that's considered negative vertical. Beyond this
        // angle will not result in any orientation changes. If phone faces uses,
        // the device is leaning forward.
        private static final int PIVOT_LOWER = 0;
        // Upper threshold limit for switching from portrait to landscape
        private static final int PL_UPPER = 280;
        // Lower threshold limit for switching from landscape to portrait
        private static final int LP_LOWER = 320;
        // Lower threshold limt for switching from portrait to landscape
        private static final int PL_LOWER = 240;
        // Upper threshold limit for switching from landscape to portrait
        private static final int LP_UPPER = 360;
        
        // Internal value used for calculating linear variant
        private static final float PL_LINEAR_FACTOR =
            ((float)(PL_UPPER-PL_LOWER))/((float)(PIVOT_UPPER-PIVOT_LOWER));
        //  Internal value used for calculating linear variant
        private static final float LP_LINEAR_FACTOR =
            ((float)(LP_UPPER - LP_LOWER))/((float)(PIVOT_UPPER-PIVOT_LOWER));
        
        public void onSensorChanged(SensorEvent event) {
            float[] values = event.values;
            float X = values[_DATA_X];
            float Y = values[_DATA_Y];
            float Z = values[_DATA_Z];
            float OneEightyOverPi = 57.29577957855f;
            float gravity = (float) Math.sqrt(X*X+Y*Y+Z*Z);
            float zyangle = Math.abs((float)Math.asin(Z/gravity)*OneEightyOverPi);
            int rotation = mSensorRotation;
            if ((zyangle <= PIVOT_UPPER) && (zyangle >= PIVOT_LOWER)) {
                // Check orientation only if the phone is flat enough
                // Don't trust the angle if the magnitude is small compared to the y value
                float angle = (float)Math.atan2(Y, -X) * OneEightyOverPi;
                int orientation = 90 - (int)Math.round(angle);
                // normalize to 0 - 359 range
                while (orientation >= 360) {
                    orientation -= 360;
                } 
                while (orientation < 0) {
                    orientation += 360;
                }
               
                float delta = (float)Math.abs(zyangle - PIVOT);
                if (((orientation >= 0) && (orientation <= LP_UPPER)) ||
                        (orientation >= PL_LOWER)) {
                    float threshold;
                    if (mSensorRotation == Surface.ROTATION_90) {
                        threshold = LP_LOWER + (LP_LINEAR_FACTOR * delta) ;
                    } else {
                        threshold = PL_UPPER - (PL_LINEAR_FACTOR * delta);
                    }
                    rotation = (orientation >= PL_LOWER &&
                            orientation <= threshold) ? Surface.ROTATION_90 : Surface.ROTATION_0;
                }
                
            }
            if (rotation != mSensorRotation) {
                mSensorRotation = rotation;
                onOrientationChanged(mSensorRotation);
            }
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    }

    /*
     * Returns true if sensor is enabled and false otherwise
     */
    public boolean canDetectOrientation() {
        return mSensor != null;
    }
    
    /**
     * Called when the rotation view of the device has changed.
     * Can be either Surface.ROTATION_90 or Surface.ROTATION_0.
     * @param rotation The new orientation of the device.
     *
     *  @see #ORIENTATION_UNKNOWN
     */
    abstract public void onOrientationChanged(int rotation);
}
