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
     *
     * This constructor is private since no one uses it and making it public would complicate
     * things, since the lowpass filtering code depends on the actual sampling period, and there's
     * no way to get the period from SensorManager based on the rate constant.
     */
    private WindowOrientationListener(Context context, int rate) {
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
        // We work with all angles in degrees in this class.
        private static final float RADIANS_TO_DEGREES = (float) (180 / Math.PI);

        // Indices into SensorEvent.values
        private static final int _DATA_X = 0;
        private static final int _DATA_Y = 1;
        private static final int _DATA_Z = 2;

        // Internal aliases for the four orientation states.  ROTATION_0 = default portrait mode,
        // ROTATION_90 = right side of device facing the sky, etc.
        private static final int ROTATION_0 = 0;
        private static final int ROTATION_90 = 1;
        private static final int ROTATION_270 = 2;

        // Current orientation state
        private int mRotation = ROTATION_0;

        // Mapping our internal aliases into actual Surface rotation values
        private final int[] SURFACE_ROTATIONS = new int[] {Surface.ROTATION_0, Surface.ROTATION_90,
                Surface.ROTATION_270};

        // Threshold ranges of orientation angle to transition into other orientation states.
        // The first list is for transitions from ROTATION_0, the next for ROTATION_90, etc.
        // ROTATE_TO defines the orientation each threshold range transitions to, and must be kept
        // in sync with this.
        // The thresholds are nearly regular -- we generally transition about the halfway point
        // between two states with a swing of 30 degrees for hysteresis.  For ROTATION_180,
        // however, we enforce stricter thresholds, pushing the thresholds 15 degrees closer to 180.
        private final int[][][] THRESHOLDS = new int[][][] {
                {{60, 180}, {180, 300}},
                {{0, 45}, {45, 165}, {330, 360}},
                {{0, 30}, {195, 315}, {315, 360}}
        };

        // See THRESHOLDS
        private final int[][] ROTATE_TO = new int[][] {
                {ROTATION_270, ROTATION_90},
                {ROTATION_0, ROTATION_270, ROTATION_0},
                {ROTATION_0, ROTATION_90, ROTATION_0}
        };

        // Maximum absolute tilt angle at which to consider orientation changes.  Beyond this (i.e.
        // when screen is facing the sky or ground), we refuse to make any orientation changes.
        private static final int MAX_TILT = 65;

        // Additional limits on tilt angle to transition to each new orientation.  We ignore all
        // vectors with tilt beyond MAX_TILT, but we can set stricter limits on transition to a
        // particular orientation here.
        private final int[] MAX_TRANSITION_TILT = new int[] {MAX_TILT, MAX_TILT, MAX_TILT};

        // Between this tilt angle and MAX_TILT, we'll allow orientation changes, but we'll filter
        // with a higher time constant, making us less sensitive to change.  This primarily helps
        // prevent momentary orientation changes when placing a device on a table from the side (or
        // picking one up).
        private static final int PARTIAL_TILT = 45;

        // Maximum allowable deviation of the magnitude of the sensor vector from that of gravity,
        // in m/s^2.  Beyond this, we assume the phone is under external forces and we can't trust
        // the sensor data.  However, under constantly vibrating conditions (think car mount), we
        // still want to pick up changes, so rather than ignore the data, we filter it with a very
        // high time constant.
        private static final int MAX_DEVIATION_FROM_GRAVITY = 1;

        // Actual sampling period corresponding to SensorManager.SENSOR_DELAY_NORMAL.  There's no
        // way to get this information from SensorManager.
        // Note the actual period is generally 3-30ms larger than this depending on the device, but
        // that's not enough to significantly skew our results.
        private static final int SAMPLING_PERIOD_MS = 200;

        // The following time constants are all used in low-pass filtering the accelerometer output.
        // See http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization for
        // background.

        // When device is near-vertical (screen approximately facing the horizon)
        private static final int DEFAULT_TIME_CONSTANT_MS = 200;
        // When device is partially tilted towards the sky or ground
        private static final int TILTED_TIME_CONSTANT_MS = 600;
        // When device is under external acceleration, i.e. not just gravity.  We heavily distrust
        // such readings.
        private static final int ACCELERATING_TIME_CONSTANT_MS = 5000;

        private static final float DEFAULT_LOWPASS_ALPHA =
            (float) SAMPLING_PERIOD_MS / (DEFAULT_TIME_CONSTANT_MS + SAMPLING_PERIOD_MS);
        private static final float TILTED_LOWPASS_ALPHA =
            (float) SAMPLING_PERIOD_MS / (TILTED_TIME_CONSTANT_MS + SAMPLING_PERIOD_MS);
        private static final float ACCELERATING_LOWPASS_ALPHA =
            (float) SAMPLING_PERIOD_MS / (ACCELERATING_TIME_CONSTANT_MS + SAMPLING_PERIOD_MS);

        // The low-pass filtered accelerometer data
        private float[] mFilteredVector = new float[] {0, 0, 0};

        int getCurrentRotation() {
            return SURFACE_ROTATIONS[mRotation];
        }

        private void calculateNewRotation(int orientation, int tiltAngle) {
            if (localLOGV) Log.i(TAG, orientation + ", " + tiltAngle + ", " + mRotation);
            int thresholdRanges[][] = THRESHOLDS[mRotation];
            int row = -1;
            for (int i = 0; i < thresholdRanges.length; i++) {
                if (orientation >= thresholdRanges[i][0] && orientation < thresholdRanges[i][1]) {
                    row = i;
                    break;
                }
            }
            if (row == -1) return; // no matching transition

            int rotation = ROTATE_TO[mRotation][row];
            if (tiltAngle > MAX_TRANSITION_TILT[rotation]) {
                // tilted too far flat to go to this rotation
                return;
            }

            if (localLOGV) Log.i(TAG, " new rotation = " + rotation);
            mRotation = rotation;
            onOrientationChanged(SURFACE_ROTATIONS[rotation]);
        }

        private float lowpassFilter(float newValue, float oldValue, float alpha) {
            return alpha * newValue + (1 - alpha) * oldValue;
        }

        private float vectorMagnitude(float x, float y, float z) {
            return (float) Math.sqrt(x*x + y*y + z*z);
        }

        /**
         * Absolute angle between upVector and the x-y plane (the plane of the screen), in [0, 90].
         * 90 degrees = screen facing the sky or ground.
         */
        private float tiltAngle(float z, float magnitude) {
            return Math.abs((float) Math.asin(z / magnitude) * RADIANS_TO_DEGREES);
        }

        public void onSensorChanged(SensorEvent event) {
            // the vector given in the SensorEvent points straight up (towards the sky) under ideal
            // conditions (the phone is not accelerating).  i'll call this upVector elsewhere.
            float x = event.values[_DATA_X];
            float y = event.values[_DATA_Y];
            float z = event.values[_DATA_Z];
            float magnitude = vectorMagnitude(x, y, z);
            float deviation = Math.abs(magnitude - SensorManager.STANDARD_GRAVITY);
            float tiltAngle = tiltAngle(z, magnitude);

            float alpha = DEFAULT_LOWPASS_ALPHA;
            if (tiltAngle > MAX_TILT) {
                return;
            } else if (deviation > MAX_DEVIATION_FROM_GRAVITY) {
                alpha = ACCELERATING_LOWPASS_ALPHA;
            } else if (tiltAngle > PARTIAL_TILT) {
                alpha = TILTED_LOWPASS_ALPHA;
            }

            x = mFilteredVector[0] = lowpassFilter(x, mFilteredVector[0], alpha);
            y = mFilteredVector[1] = lowpassFilter(y, mFilteredVector[1], alpha);
            z = mFilteredVector[2] = lowpassFilter(z, mFilteredVector[2], alpha);
            magnitude = vectorMagnitude(x, y, z);
            tiltAngle = tiltAngle(z, magnitude);

            // Angle between the x-y projection of upVector and the +y-axis, increasing
            // counter-clockwise.
            // 0 degrees = speaker end towards the sky
            // 90 degrees = left edge of device towards the sky
            float orientationAngle = (float) Math.atan2(-x, y) * RADIANS_TO_DEGREES;
            int orientation = Math.round(orientationAngle);
            // atan2 returns (-180, 180]; normalize to [0, 360)
            if (orientation < 0) {
                orientation += 360;
            }
            calculateNewRotation(orientation, Math.round(tiltAngle));
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
     *
     * @param rotation The new orientation of the device, one of the Surface.ROTATION_* constants.
     * @see Surface
     */
    abstract public void onOrientationChanged(int rotation);
}
