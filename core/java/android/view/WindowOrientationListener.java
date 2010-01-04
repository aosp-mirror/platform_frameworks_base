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
    private static final boolean DEBUG = true;
    private static final boolean localLOGV = DEBUG || Config.DEBUG;
    private SensorManager mSensorManager;
    private boolean mEnabled = false;
    private int mRate;
    private Sensor mSensor;
    private SensorEventListenerImpl mSensorEventListener;

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

    public int getCurrentRotation() {
        if (mEnabled) {
            return mSensorEventListener.getCurrentRotation();
        }
        return -1;
    }
    
    class SensorEventListenerImpl implements SensorEventListener {
        private static final int _DATA_X = 0;
        private static final int _DATA_Y = 1;
        private static final int _DATA_Z = 2;
        // Angle around x-asis that's considered almost too vertical. Beyond
        // this angle will not result in any orientation changes. f phone faces uses,
        // the device is leaning backward.
        private static final int PIVOT_UPPER = 65;
        // Angle about x-axis that's considered negative vertical. Beyond this
        // angle will not result in any orientation changes. If phone faces uses,
        // the device is leaning forward.
        private static final int PIVOT_LOWER = -10;
        static final int ROTATION_0 = 0;
        static final int ROTATION_90 = 1;
        static final int ROTATION_180 = 2;
        static final int ROTATION_270 = 3;
        int mRotation = ROTATION_0;

        // Threshold values defined for device rotation positions
        // follow order ROTATION_0 .. ROTATION_270
        final int THRESHOLDS[][][] = new int[][][] {
            {{60, 135}, {135, 225}, {225, 300}},
                {{0, 45}, {45, 135}, {135, 210}, {330, 360}},
                {{0, 45}, {45, 120}, {240, 315}, {315, 360}},
                {{0, 30}, {150, 225}, {225, 315}, {315, 360}}
        };

        // Transform rotation ranges based on THRESHOLDS. This
        // has to be in step with THESHOLDS
        final int ROTATE_TO[][] = new int[][] {
            {ROTATION_270, ROTATION_180, ROTATION_90},
            {ROTATION_0, ROTATION_270, ROTATION_180, ROTATION_0},
            {ROTATION_0, ROTATION_270, ROTATION_90, ROTATION_0},
            {ROTATION_0, ROTATION_180, ROTATION_90, ROTATION_0}
        };

        // Mapping into actual Surface rotation values
        final int TRANSFORM_ROTATIONS[] = new int[]{Surface.ROTATION_0,
                Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270};

        int getCurrentRotation() {
            return TRANSFORM_ROTATIONS[mRotation];
        }
        
        private void calculateNewRotation(int orientation, int zyangle) {
            if (localLOGV) Log.i(TAG, orientation + ", " + zyangle + ", " + mRotation);
            int rangeArr[][] = THRESHOLDS[mRotation];
            int row = -1;
            for (int i = 0; i < rangeArr.length; i++) {
                if ((orientation >= rangeArr[i][0]) && (orientation < rangeArr[i][1])) {
                    row = i;
                    break;
                }
            }
            if (row != -1) {
                // Find new rotation based on current rotation value.
                // This also takes care of irregular rotations as well.
                int rotation = ROTATE_TO[mRotation][row];
                if (localLOGV) Log.i(TAG, " new rotation = " + rotation);
                if (rotation != mRotation) {
                    mRotation = rotation;
                    // Trigger orientation change
                    onOrientationChanged(TRANSFORM_ROTATIONS[rotation]);
                }
            }
        }

        public void onSensorChanged(SensorEvent event) {
            float[] values = event.values;
            float X = values[_DATA_X];
            float Y = values[_DATA_Y];
            float Z = values[_DATA_Z];
            float OneEightyOverPi = 57.29577957855f;
            float gravity = (float) Math.sqrt(X*X+Y*Y+Z*Z);
            float zyangle = (float)Math.asin(Z/gravity)*OneEightyOverPi;
            if ((zyangle <= PIVOT_UPPER) && (zyangle >= PIVOT_LOWER)) {
                // Check orientation only if the phone is flat enough
                // Don't trust the angle if the magnitude is small compared to the y value
                float angle = (float)Math.atan2(Y, -X) * OneEightyOverPi;
                int orientation = 90 - Math.round(angle);
                // normalize to 0 - 359 range
                while (orientation >= 360) {
                    orientation -= 360;
                }
                while (orientation < 0) {
                    orientation += 360;
                }
                calculateNewRotation(orientation, Math.round(zyangle));
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
