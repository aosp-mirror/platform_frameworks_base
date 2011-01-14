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
 *
 * NOTE: If changing anything here, please run the API demo
 * "App/Activity/Screen Orientation" to ensure that all orientation
 * modes still work correctly.
 *
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
            mSensorEventListener = new SensorEventListenerImpl(this);
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

    public void setAllow180Rotation(boolean allowed) {
        if (mSensorEventListener != null) {
            mSensorEventListener.setAllow180Rotation(allowed);
        }
    }

    public int getCurrentRotation(int lastRotation) {
        if (mEnabled) {
            return mSensorEventListener.getCurrentRotation(lastRotation);
        }
        return lastRotation;
    }

    /**
     * This class filters the raw accelerometer data and tries to detect actual changes in
     * orientation. This is a very ill-defined problem so there are a lot of tweakable parameters,
     * but here's the outline:
     *
     *  - Convert the acceleromter vector from cartesian to spherical coordinates. Since we're
     * dealing with rotation of the device, this is the sensible coordinate system to work in. The
     * zenith direction is the Z-axis, i.e. the direction the screen is facing. The radial distance
     * is referred to as the magnitude below. The elevation angle is referred to as the "tilt"
     * below. The azimuth angle is referred to as the "orientation" below (and the azimuth axis is
     * the Y-axis). See http://en.wikipedia.org/wiki/Spherical_coordinate_system for reference.
     *
     *  - Low-pass filter the tilt and orientation angles to avoid "twitchy" behavior.
     *
     *  - When the orientation angle reaches a certain threshold, transition to the corresponding
     * orientation. These thresholds have some hysteresis built-in to avoid oscillation.
     *
     *  - Use the magnitude to judge the accuracy of the data. Under ideal conditions, the magnitude
     * should equal to that of gravity. When it differs significantly, we know the device is under
     * external acceleration and we can't trust the data.
     *
     *  - Use the tilt angle to judge the accuracy of orientation data. When the tilt angle is high
     * in magnitude, we distrust the orientation data, because when the device is nearly flat, small
     * physical movements produce large changes in orientation angle.
     *
     * Details are explained below.
     */
    static class SensorEventListenerImpl implements SensorEventListener {
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
        private static final int ROTATION_180 = 3;

        // Mapping our internal aliases into actual Surface rotation values
        private static final int[] INTERNAL_TO_SURFACE_ROTATION = new int[] {
            Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_270,
            Surface.ROTATION_180};

        // Mapping Surface rotation values to internal aliases.
        private static final int[] SURFACE_TO_INTERNAL_ROTATION = new int[] {
            ROTATION_0, ROTATION_90, ROTATION_180, ROTATION_270};

        // Threshold ranges of orientation angle to transition into other orientation states.
        // The first list is for transitions from ROTATION_0, ROTATION_90, ROTATION_270,
        // and then ROTATION_180.
        // ROTATE_TO defines the orientation each threshold range transitions to, and must be kept
        // in sync with this.
        // We generally transition about the halfway point between two states with a swing of 30
        // degrees for hysteresis.
        private static final int[][][] THRESHOLDS = new int[][][] {
                {{60, 180}, {180, 300}},
                {{0, 30}, {195, 315}, {315, 360}},
                {{0, 45}, {45, 165}, {330, 360}},

                // Handle situation where we are currently doing 180 rotation
                // but that is no longer allowed.
                {{0, 45}, {45, 135}, {135, 225}, {225, 315}, {315, 360}},
        };
        // See THRESHOLDS
        private static final int[][] ROTATE_TO = new int[][] {
                {ROTATION_90, ROTATION_270},
                {ROTATION_0, ROTATION_270, ROTATION_0},
                {ROTATION_0, ROTATION_90, ROTATION_0},
                {ROTATION_0, ROTATION_90, ROTATION_0, ROTATION_270, ROTATION_0},
        };

        // Thresholds that allow all 4 orientations.
        private static final int[][][] THRESHOLDS_WITH_180 = new int[][][] {
            {{60, 165}, {165, 195}, {195, 300}},
            {{0, 30}, {165, 195}, {195, 315}, {315, 360}},
            {{0, 45}, {45, 165}, {165, 195}, {330, 360}},
            {{0, 45}, {45, 135}, {225, 315}, {315, 360}},
        };
        // See THRESHOLDS_WITH_180
        private static final int[][] ROTATE_TO_WITH_180 = new int[][] {
            {ROTATION_90, ROTATION_180, ROTATION_270},
            {ROTATION_0, ROTATION_180, ROTATION_90, ROTATION_0},
            {ROTATION_0, ROTATION_270, ROTATION_180, ROTATION_0},
            {ROTATION_0, ROTATION_90, ROTATION_270, ROTATION_0},
        };

        // Maximum absolute tilt angle at which to consider orientation data.  Beyond this (i.e.
        // when screen is facing the sky or ground), we completely ignore orientation data.
        private static final int MAX_TILT = 75;

        // Additional limits on tilt angle to transition to each new orientation.  We ignore all
        // data with tilt beyond MAX_TILT, but we can set stricter limits on transitions to a
        // particular orientation here.
        private static final int[] MAX_TRANSITION_TILT = new int[] {MAX_TILT, 65, 65, 40};

        // Between this tilt angle and MAX_TILT, we'll allow orientation changes, but we'll filter
        // with a higher time constant, making us less sensitive to change.  This primarily helps
        // prevent momentary orientation changes when placing a device on a table from the side (or
        // picking one up).
        private static final int PARTIAL_TILT = 50;

        // Maximum allowable deviation of the magnitude of the sensor vector from that of gravity,
        // in m/s^2.  Beyond this, we assume the phone is under external forces and we can't trust
        // the sensor data.  However, under constantly vibrating conditions (think car mount), we
        // still want to pick up changes, so rather than ignore the data, we filter it with a very
        // high time constant.
        private static final float MAX_DEVIATION_FROM_GRAVITY = 1.5f;

        // Minimum acceleration considered, in m/s^2. Below this threshold sensor noise will have
        // significant impact on the calculations and in case of the vector (0, 0, 0) there is no
        // defined rotation or tilt at all. Low or zero readings can happen when space travelling
        // or free falling, but more commonly when shaking or getting bad readings from the sensor.
        // The accelerometer is turned off when not used and polling it too soon after it is
        // turned on may result in (0, 0, 0).
        private static final float MIN_ABS_ACCELERATION = 1.5f;

        // Actual sampling period corresponding to SensorManager.SENSOR_DELAY_NORMAL.  There's no
        // way to get this information from SensorManager.
        // Note the actual period is generally 3-30ms larger than this depending on the device, but
        // that's not enough to significantly skew our results.
        private static final int SAMPLING_PERIOD_MS = 200;

        // The following time constants are all used in low-pass filtering the accelerometer output.
        // See http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization for
        // background.

        // When device is near-vertical (screen approximately facing the horizon)
        private static final int DEFAULT_TIME_CONSTANT_MS = 100;
        // When device is partially tilted towards the sky or ground
        private static final int TILTED_TIME_CONSTANT_MS = 500;
        // When device is under external acceleration, i.e. not just gravity.  We heavily distrust
        // such readings.
        private static final int ACCELERATING_TIME_CONSTANT_MS = 2000;

        private static final float DEFAULT_LOWPASS_ALPHA =
            computeLowpassAlpha(DEFAULT_TIME_CONSTANT_MS);
        private static final float TILTED_LOWPASS_ALPHA =
            computeLowpassAlpha(TILTED_TIME_CONSTANT_MS);
        private static final float ACCELERATING_LOWPASS_ALPHA =
            computeLowpassAlpha(ACCELERATING_TIME_CONSTANT_MS);

        private boolean mAllow180Rotation = false;

        private WindowOrientationListener mOrientationListener;
        private int mRotation = ROTATION_0; // Current orientation state
        private float mTiltAngle = 0; // low-pass filtered
        private float mOrientationAngle = 0; // low-pass filtered

        /*
         * Each "distrust" counter represents our current level of distrust in the data based on
         * a certain signal.  For each data point that is deemed unreliable based on that signal,
         * the counter increases; otherwise, the counter decreases.  Exact rules vary.
         */
        private int mAccelerationDistrust = 0; // based on magnitude != gravity
        private int mTiltDistrust = 0; // based on tilt close to +/- 90 degrees

        public SensorEventListenerImpl(WindowOrientationListener orientationListener) {
            mOrientationListener = orientationListener;
        }

        private static float computeLowpassAlpha(int timeConstantMs) {
            return (float) SAMPLING_PERIOD_MS / (timeConstantMs + SAMPLING_PERIOD_MS);
        }

        void setAllow180Rotation(boolean allowed) {
            mAllow180Rotation = allowed;
        }

        int getCurrentRotation(int lastRotation) {
            if (mTiltDistrust > 0) {
                // we really don't know the current orientation, so trust what's currently displayed
                mRotation = SURFACE_TO_INTERNAL_ROTATION[lastRotation];
            }
            return INTERNAL_TO_SURFACE_ROTATION[mRotation];
        }

        private void calculateNewRotation(float orientation, float tiltAngle) {
            if (localLOGV) Log.i(TAG, orientation + ", " + tiltAngle + ", " + mRotation);
            final boolean allow180Rotation = mAllow180Rotation;
            int thresholdRanges[][] = allow180Rotation
                    ? THRESHOLDS_WITH_180[mRotation] : THRESHOLDS[mRotation];
            int row = -1;
            for (int i = 0; i < thresholdRanges.length; i++) {
                if (orientation >= thresholdRanges[i][0] && orientation < thresholdRanges[i][1]) {
                    row = i;
                    break;
                }
            }
            if (row == -1) return; // no matching transition

            int rotation = allow180Rotation
                    ? ROTATE_TO_WITH_180[mRotation][row] : ROTATE_TO[mRotation][row];
            if (tiltAngle > MAX_TRANSITION_TILT[rotation]) {
                // tilted too far flat to go to this rotation
                return;
            }

            if (localLOGV) Log.i(TAG, "orientation " + orientation + " gives new rotation = "
                    + rotation);
            mRotation = rotation;
            mOrientationListener.onOrientationChanged(INTERNAL_TO_SURFACE_ROTATION[mRotation]);
        }

        private float lowpassFilter(float newValue, float oldValue, float alpha) {
            return alpha * newValue + (1 - alpha) * oldValue;
        }

        private float vectorMagnitude(float x, float y, float z) {
            return (float) Math.sqrt(x*x + y*y + z*z);
        }

        /**
         * Angle between upVector and the x-y plane (the plane of the screen), in [-90, 90].
         * +/- 90 degrees = screen facing the sky or ground.
         */
        private float tiltAngle(float z, float magnitude) {
            return (float) Math.asin(z / magnitude) * RADIANS_TO_DEGREES;
        }

        public void onSensorChanged(SensorEvent event) {
            // the vector given in the SensorEvent points straight up (towards the sky) under ideal
            // conditions (the phone is not accelerating).  i'll call this upVector elsewhere.
            float x = event.values[_DATA_X];
            float y = event.values[_DATA_Y];
            float z = event.values[_DATA_Z];
            float magnitude = vectorMagnitude(x, y, z);
            float deviation = Math.abs(magnitude - SensorManager.STANDARD_GRAVITY);

            handleAccelerationDistrust(deviation);
            if (magnitude < MIN_ABS_ACCELERATION) {
                return; // Ignore tilt and orientation when (0, 0, 0) or low reading
            }

            // only filter tilt when we're accelerating
            float alpha = 1;
            if (mAccelerationDistrust > 0) {
                alpha = ACCELERATING_LOWPASS_ALPHA;
            }
            float newTiltAngle = tiltAngle(z, magnitude);
            mTiltAngle = lowpassFilter(newTiltAngle, mTiltAngle, alpha);

            float absoluteTilt = Math.abs(mTiltAngle);
            checkFullyTilted(absoluteTilt);
            if (mTiltDistrust > 0) {
                return; // when fully tilted, ignore orientation entirely
            }

            float newOrientationAngle = computeNewOrientation(x, y);
            filterOrientation(absoluteTilt, newOrientationAngle);
            calculateNewRotation(mOrientationAngle, absoluteTilt);
        }

        /**
         * When accelerating, increment distrust; otherwise, decrement distrust.  The idea is that
         * if a single jolt happens among otherwise good data, we should keep trusting the good
         * data.  On the other hand, if a series of many bad readings comes in (as if the phone is
         * being rapidly shaken), we should wait until things "settle down", i.e. we get a string
         * of good readings.
         *
         * @param deviation absolute difference between the current magnitude and gravity
         */
        private void handleAccelerationDistrust(float deviation) {
            if (deviation > MAX_DEVIATION_FROM_GRAVITY) {
                if (mAccelerationDistrust < 5) {
                    mAccelerationDistrust++;
                }
            } else if (mAccelerationDistrust > 0) {
                mAccelerationDistrust--;
            }
        }

        /**
         * Check if the phone is tilted towards the sky or ground and handle that appropriately.
         * When fully tilted, we automatically push the tilt up to a fixed value; otherwise we
         * decrement it.  The idea is to distrust the first few readings after the phone gets
         * un-tilted, no matter what, i.e. preventing an accidental transition when the phone is
         * picked up from a table.
         *
         * We also reset the orientation angle to the center of the current screen orientation.
         * Since there is no real orientation of the phone, we want to ignore the most recent sensor
         * data and reset it to this value to avoid a premature transition when the phone starts to
         * get un-tilted.
         *
         * @param absoluteTilt the absolute value of the current tilt angle
         */
        private void checkFullyTilted(float absoluteTilt) {
            if (absoluteTilt > MAX_TILT) {
                if (mRotation == ROTATION_0) {
                    mOrientationAngle = 0;
                } else if (mRotation == ROTATION_90) {
                    mOrientationAngle = 90;
                } else { // ROTATION_270
                    mOrientationAngle = 270;
                }

                if (mTiltDistrust < 3) {
                    mTiltDistrust = 3;
                }
            } else if (mTiltDistrust > 0) {
                mTiltDistrust--;
            }
        }

        /**
         * Angle between the x-y projection of upVector and the +y-axis, increasing
         * clockwise.
         * 0 degrees = speaker end towards the sky
         * 90 degrees = right edge of device towards the sky
         */
        private float computeNewOrientation(float x, float y) {
            float orientationAngle = (float) -Math.atan2(-x, y) * RADIANS_TO_DEGREES;
            // atan2 returns [-180, 180]; normalize to [0, 360]
            if (orientationAngle < 0) {
                orientationAngle += 360;
            }
            return orientationAngle;
        }

        /**
         * Compute a new filtered orientation angle.
         */
        private void filterOrientation(float absoluteTilt, float orientationAngle) {
            float alpha = DEFAULT_LOWPASS_ALPHA;
            if (mAccelerationDistrust > 1) {
                // when under more than a transient acceleration, distrust heavily
                alpha = ACCELERATING_LOWPASS_ALPHA;
            } else if (absoluteTilt > PARTIAL_TILT || mAccelerationDistrust == 1) {
                // when tilted partway, or under transient acceleration, distrust lightly
                alpha = TILTED_LOWPASS_ALPHA;
            }

            // since we're lowpass filtering a value with periodic boundary conditions, we need to
            // adjust the new value to filter in the right direction...
            float deltaOrientation = orientationAngle - mOrientationAngle;
            if (deltaOrientation > 180) {
                orientationAngle -= 360;
            } else if (deltaOrientation < -180) {
                orientationAngle += 360;
            }
            mOrientationAngle = lowpassFilter(orientationAngle, mOrientationAngle, alpha);
            // ...and then adjust back to ensure we're in the range [0, 360]
            if (mOrientationAngle > 360) {
                mOrientationAngle -= 360;
            } else if (mOrientationAngle < 0) {
                mOrientationAngle += 360;
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
     *
     * @param rotation The new orientation of the device, one of the Surface.ROTATION_* constants.
     * @see Surface
     */
    abstract public void onOrientationChanged(int rotation);
}
