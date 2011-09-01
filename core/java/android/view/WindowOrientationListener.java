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
import android.util.Log;
import android.util.Slog;

/**
 * A special helper class used by the WindowManager
 *  for receiving notifications from the SensorManager when
 * the orientation of the device has changed.
 *
 * NOTE: If changing anything here, please run the API demo
 * "App/Activity/Screen Orientation" to ensure that all orientation
 * modes still work correctly.
 *
 * You can also visualize the behavior of the WindowOrientationListener by
 * enabling the window orientation listener log using the Development Settings
 * in the Dev Tools application (Development.apk)
 * and running frameworks/base/tools/orientationplot/orientationplot.py.
 *
 * More information about how to tune this algorithm in
 * frameworks/base/tools/orientationplot/README.txt.
 *
 * @hide
 */
public abstract class WindowOrientationListener {
    private static final String TAG = "WindowOrientationListener";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG || false;

    private SensorManager mSensorManager;
    private boolean mEnabled;
    private int mRate;
    private Sensor mSensor;
    private SensorEventListenerImpl mSensorEventListener;
    boolean mLogEnabled;

    /**
     * Creates a new WindowOrientationListener.
     * 
     * @param context for the WindowOrientationListener.
     */
    public WindowOrientationListener(Context context) {
        this(context, SensorManager.SENSOR_DELAY_UI);
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
     * This constructor is private since no one uses it.
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

    /**
     * Gets the current orientation.
     * @param lastRotation
     * @return
     */
    public int getCurrentRotation(int lastRotation) {
        if (mEnabled) {
            return mSensorEventListener.getCurrentRotation(lastRotation);
        }
        return lastRotation;
    }

    /**
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
    public abstract void onOrientationChanged(int rotation);

    /**
     * Enables or disables the window orientation listener logging for use with
     * the orientationplot.py tool.
     * Logging is usually enabled via Development Settings.  (See class comments.)
     * @param enable True to enable logging.
     */
    public void setLogEnabled(boolean enable) {
        mLogEnabled = enable;
    }

    /**
     * This class filters the raw accelerometer data and tries to detect actual changes in
     * orientation. This is a very ill-defined problem so there are a lot of tweakable parameters,
     * but here's the outline:
     *
     *  - Low-pass filter the accelerometer vector in cartesian coordinates.  We do it in
     *    cartesian space because the orientation calculations are sensitive to the
     *    absolute magnitude of the acceleration.  In particular, there are singularities
     *    in the calculation as the magnitude approaches 0.  By performing the low-pass
     *    filtering early, we can eliminate high-frequency impulses systematically.
     *
     *  - Convert the acceleromter vector from cartesian to spherical coordinates.
     *    Since we're dealing with rotation of the device, this is the sensible coordinate
     *    system to work in.  The zenith direction is the Z-axis, the direction the screen
     *    is facing.  The radial distance is referred to as the magnitude below.
     *    The elevation angle is referred to as the "tilt" below.
     *    The azimuth angle is referred to as the "orientation" below (and the azimuth axis is
     *    the Y-axis).
     *    See http://en.wikipedia.org/wiki/Spherical_coordinate_system for reference.
     *
     *  - If the tilt angle is too close to horizontal (near 90 or -90 degrees), do nothing.
     *    The orientation angle is not meaningful when the device is nearly horizontal.
     *    The tilt angle thresholds are set differently for each orientation and different
     *    limits are applied when the device is facing down as opposed to when it is facing
     *    forward or facing up.
     *
     *  - When the orientation angle reaches a certain threshold, consider transitioning
     *    to the corresponding orientation.  These thresholds have some hysteresis built-in
     *    to avoid oscillations between adjacent orientations.
     *
     *  - Use the magnitude to judge the confidence of the orientation.
     *    Under ideal conditions, the magnitude should equal to that of gravity.  When it
     *    differs significantly, we know the device is under external acceleration and
     *    we can't trust the data.
     *
     *  - Use the tilt angle to judge the confidence of the orientation.
     *    When the tilt angle is high in absolute value then the device is nearly flat
     *    so small physical movements produce large changes in orientation angle.
     *    This can be the case when the device is being picked up from a table.
     *
     *  - Use the orientation angle to judge the confidence of the orientation.
     *    The close the orientation angle is to the canonical orientation angle, the better.
     *
     *  - Based on the aggregate confidence, we determine how long we want to wait for
     *    the new orientation to settle.  This is accomplished by integrating the confidence
     *    for each orientation over time.  When a threshold integration sum is reached
     *    then we actually change orientations.
     *
     * Details are explained inline.
     */
    static final class SensorEventListenerImpl implements SensorEventListener {
        // We work with all angles in degrees in this class.
        private static final float RADIANS_TO_DEGREES = (float) (180 / Math.PI);

        // Indices into SensorEvent.values for the accelerometer sensor.
        private static final int ACCELEROMETER_DATA_X = 0;
        private static final int ACCELEROMETER_DATA_Y = 1;
        private static final int ACCELEROMETER_DATA_Z = 2;

        // Rotation constants.
        // These are the same as Surface rotation constants with the addition of a 5th
        // unknown state when we are not confident about the proporsed orientation.
        // One important property of these constants is that they are equal to the
        // orientation angle itself divided by 90.  We use this fact to map
        // back and forth between orientation angles and rotation values.
        private static final int ROTATION_UNKNOWN = -1;
        //private static final int ROTATION_0 = Surface.ROTATION_0; // 0
        //private static final int ROTATION_90 = Surface.ROTATION_90; // 1
        //private static final int ROTATION_180 = Surface.ROTATION_180; // 2
        //private static final int ROTATION_270 = Surface.ROTATION_270; // 3

        private final WindowOrientationListener mOrientationListener;

        private int mRotation = ROTATION_UNKNOWN;

        /* State for first order low-pass filtering of accelerometer data.
         * See http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization for
         * signal processing background.
         */

        private long mLastTimestamp = Long.MAX_VALUE; // in nanoseconds
        private float mLastFilteredX, mLastFilteredY, mLastFilteredZ;

        // The maximum sample inter-arrival time in milliseconds.
        // If the acceleration samples are further apart than this amount in time, we reset the
        // state of the low-pass filter and orientation properties.  This helps to handle
        // boundary conditions when the device is turned on, wakes from suspend or there is
        // a significant gap in samples.
        private static final float MAX_FILTER_DELTA_TIME_MS = 1000;

        // The acceleration filter cutoff frequency.
        // This is the frequency at which signals are attenuated by 3dB (half the passband power).
        // Each successive octave beyond this frequency is attenuated by an additional 6dB.
        //
        // We choose the cutoff frequency such that impulses and vibrational noise
        // (think car dock) is suppressed.  However, this filtering does not eliminate
        // all possible sources of orientation ambiguity so we also rely on a dynamic
        // settle time for establishing a new orientation.  Filtering adds latency
        // inversely proportional to the cutoff frequency so we don't want to make
        // it too small or we can lose hundreds of milliseconds of responsiveness.
        private static final float FILTER_CUTOFF_FREQUENCY_HZ = 1f;
        private static final float FILTER_TIME_CONSTANT_MS = (float)(500.0f
                / (Math.PI * FILTER_CUTOFF_FREQUENCY_HZ)); // t = 1 / (2pi * Fc) * 1000ms

        // The filter gain.
        // We choose a value slightly less than unity to avoid numerical instabilities due
        // to floating-point error accumulation.
        private static final float FILTER_GAIN = 0.999f;

        /* State for orientation detection. */

        // Thresholds for minimum and maximum allowable deviation from gravity.
        //
        // If the device is undergoing external acceleration (being bumped, in a car
        // that is turning around a corner or a plane taking off) then the magnitude
        // may be substantially more or less than gravity.  This can skew our orientation
        // detection by making us think that up is pointed in a different direction.
        //
        // Conversely, if the device is in freefall, then there will be no gravity to
        // measure at all.  This is problematic because we cannot detect the orientation
        // without gravity to tell us which way is up.  A magnitude near 0 produces
        // singularities in the tilt and orientation calculations.
        //
        // In both cases, we postpone choosing an orientation.
        private static final float MIN_ACCELERATION_MAGNITUDE =
                SensorManager.STANDARD_GRAVITY * 0.5f;
        private static final float MAX_ACCELERATION_MAGNITUDE =
            SensorManager.STANDARD_GRAVITY * 1.5f;

        // Maximum absolute tilt angle at which to consider orientation data.  Beyond this (i.e.
        // when screen is facing the sky or ground), we completely ignore orientation data.
        private static final int MAX_TILT = 75;

        // The tilt angle range in degrees for each orientation.
        // Beyond these tilt angles, we don't even consider transitioning into the
        // specified orientation.  We place more stringent requirements on unnatural
        // orientations than natural ones to make it less likely to accidentally transition
        // into those states.
        // The first value of each pair is negative so it applies a limit when the device is
        // facing down (overhead reading in bed).
        // The second value of each pair is positive so it applies a limit when the device is
        // facing up (resting on a table).
        // The ideal tilt angle is 0 (when the device is vertical) so the limits establish
        // how close to vertical the device must be in order to change orientation.
        private static final int[][] TILT_TOLERANCE = new int[][] {
            /* ROTATION_0   */ { -20, 75 },
            /* ROTATION_90  */ { -20, 70 },
            /* ROTATION_180 */ { -20, 65 },
            /* ROTATION_270 */ { -20, 70 }
        };

        // The gap angle in degrees between adjacent orientation angles for hysteresis.
        // This creates a "dead zone" between the current orientation and a proposed
        // adjacent orientation.  No orientation proposal is made when the orientation
        // angle is within the gap between the current orientation and the adjacent
        // orientation.
        private static final int ADJACENT_ORIENTATION_ANGLE_GAP = 30;

        // The confidence scale factors for angle, tilt and magnitude.
        // When the distance between the actual value and the ideal value is the
        // specified delta, orientation transitions will take twice as long as they would
        // in the ideal case.  Increasing or decreasing the delta has an exponential effect
        // on each factor's influence over the transition time.

        // Transition takes 2x longer when angle is 30 degrees from ideal orientation angle.
        private static final float ORIENTATION_ANGLE_CONFIDENCE_SCALE =
                confidenceScaleFromDelta(30);

        // Transition takes 2x longer when tilt is 60 degrees from vertical.
        private static final float TILT_ANGLE_CONFIDENCE_SCALE = confidenceScaleFromDelta(60);

        // Transition takes 2x longer when acceleration is 0.5 Gs.
        private static final float MAGNITUDE_CONFIDENCE_SCALE = confidenceScaleFromDelta(
                SensorManager.STANDARD_GRAVITY * 0.5f);

        // The number of milliseconds for which a new orientation must be stable before
        // we perform an orientation change under ideal conditions.  It will take
        // proportionally longer than this to effect an orientation change when
        // the proposed orientation confidence is low.
        private static final float ORIENTATION_SETTLE_TIME_MS = 250;

        // The confidence that we have abount effecting each orientation change.
        // When one of these values exceeds 1.0, we have determined our new orientation!
        private float mConfidence[] = new float[4];

        public SensorEventListenerImpl(WindowOrientationListener orientationListener) {
            mOrientationListener = orientationListener;
        }

        public int getCurrentRotation(int lastRotation) {
            return mRotation != ROTATION_UNKNOWN ? mRotation : lastRotation;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            final boolean log = mOrientationListener.mLogEnabled;

            // The vector given in the SensorEvent points straight up (towards the sky) under ideal
            // conditions (the phone is not accelerating).  I'll call this up vector elsewhere.
            float x = event.values[ACCELEROMETER_DATA_X];
            float y = event.values[ACCELEROMETER_DATA_Y];
            float z = event.values[ACCELEROMETER_DATA_Z];

            if (log) {
                Slog.v(TAG, "Raw acceleration vector: " +
                        "x=" + x + ", y=" + y + ", z=" + z);
            }

            // Apply a low-pass filter to the acceleration up vector in cartesian space.
            // Reset the orientation listener state if the samples are too far apart in time
            // or when we see values of (0, 0, 0) which indicates that we polled the
            // accelerometer too soon after turning it on and we don't have any data yet.
            final float timeDeltaMS = (event.timestamp - mLastTimestamp) * 0.000001f;
            boolean skipSample;
            if (timeDeltaMS <= 0 || timeDeltaMS > MAX_FILTER_DELTA_TIME_MS
                    || (x == 0 && y == 0 && z == 0)) {
                if (log) {
                    Slog.v(TAG, "Resetting orientation listener.");
                }
                for (int i = 0; i < 4; i++) {
                    mConfidence[i] = 0;
                }
                skipSample = true;
            } else {
                final float alpha = timeDeltaMS
                        / (FILTER_TIME_CONSTANT_MS + timeDeltaMS) * FILTER_GAIN;
                x = alpha * (x - mLastFilteredX) + mLastFilteredX;
                y = alpha * (y - mLastFilteredY) + mLastFilteredY;
                z = alpha * (z - mLastFilteredZ) + mLastFilteredZ;
                if (log) {
                    Slog.v(TAG, "Filtered acceleration vector: " +
                            "x=" + x + ", y=" + y + ", z=" + z);
                }
                skipSample = false;
            }
            mLastTimestamp = event.timestamp;
            mLastFilteredX = x;
            mLastFilteredY = y;
            mLastFilteredZ = z;

            boolean orientationChanged = false;
            if (!skipSample) {
                // Determine a proposed orientation based on the currently available data.
                int proposedOrientation = ROTATION_UNKNOWN;
                float combinedConfidence = 1.0f;

                // Calculate the magnitude of the acceleration vector.
                final float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
                if (magnitude < MIN_ACCELERATION_MAGNITUDE
                        || magnitude > MAX_ACCELERATION_MAGNITUDE) {
                    if (log) {
                        Slog.v(TAG, "Ignoring sensor data, magnitude out of range: "
                                + "magnitude=" + magnitude);
                    }
                } else {
                    // Calculate the tilt angle.
                    // This is the angle between the up vector and the x-y plane (the plane of
                    // the screen) in a range of [-90, 90] degrees.
                    //   -90 degrees: screen horizontal and facing the ground (overhead)
                    //     0 degrees: screen vertical
                    //    90 degrees: screen horizontal and facing the sky (on table)
                   final int tiltAngle = (int) Math.round(
                           Math.asin(z / magnitude) * RADIANS_TO_DEGREES);

                   // If the tilt angle is too close to horizontal then we cannot determine
                   // the orientation angle of the screen.
                   if (Math.abs(tiltAngle) > MAX_TILT) {
                       if (log) {
                           Slog.v(TAG, "Ignoring sensor data, tilt angle too high: "
                                   + "magnitude=" + magnitude + ", tiltAngle=" + tiltAngle);
                       }
                   } else {
                       // Calculate the orientation angle.
                       // This is the angle between the x-y projection of the up vector onto
                       // the +y-axis, increasing clockwise in a range of [0, 360] degrees.
                       int orientationAngle = (int) Math.round(
                               -Math.atan2(-x, y) * RADIANS_TO_DEGREES);
                       if (orientationAngle < 0) {
                           // atan2 returns [-180, 180]; normalize to [0, 360]
                           orientationAngle += 360;
                       }

                       // Find the nearest orientation.
                       // An orientation of 0 can have a nearest angle of 0 or 360 depending
                       // on which is closer to the measured orientation angle.  We leave the
                       // nearest angle at 360 in that case since it makes the delta calculation
                       // for orientation angle confidence easier below.
                       int nearestOrientation = (orientationAngle + 45) / 90;
                       int nearestOrientationAngle = nearestOrientation * 90;
                       if (nearestOrientation == 4) {
                           nearestOrientation = 0;
                       }

                       // Determine the proposed orientation.
                       // The confidence of the proposal is 1.0 when it is ideal and it
                       // decays exponentially as the proposal moves further from the ideal
                       // angle, tilt and magnitude of the proposed orientation.
                       if (isTiltAngleAcceptable(nearestOrientation, tiltAngle)
                               && isOrientationAngleAcceptable(nearestOrientation,
                                       orientationAngle)) {
                           proposedOrientation = nearestOrientation;

                           final float idealOrientationAngle = nearestOrientationAngle;
                           final float orientationConfidence = confidence(orientationAngle,
                                   idealOrientationAngle, ORIENTATION_ANGLE_CONFIDENCE_SCALE);

                           final float idealTiltAngle = 0;
                           final float tiltConfidence = confidence(tiltAngle,
                                   idealTiltAngle, TILT_ANGLE_CONFIDENCE_SCALE);

                           final float idealMagnitude = SensorManager.STANDARD_GRAVITY;
                           final float magnitudeConfidence = confidence(magnitude,
                                   idealMagnitude, MAGNITUDE_CONFIDENCE_SCALE);

                           combinedConfidence = orientationConfidence
                                   * tiltConfidence * magnitudeConfidence;

                           if (log) {
                               Slog.v(TAG, "Proposal: "
                                       + "magnitude=" + magnitude
                                       + ", tiltAngle=" + tiltAngle
                                       + ", orientationAngle=" + orientationAngle
                                       + ", proposedOrientation=" + proposedOrientation
                                       + ", combinedConfidence=" + combinedConfidence
                                       + ", orientationConfidence=" + orientationConfidence
                                       + ", tiltConfidence=" + tiltConfidence
                                       + ", magnitudeConfidence=" + magnitudeConfidence);
                           }
                       } else {
                           if (log) {
                               Slog.v(TAG, "Ignoring sensor data, no proposal: "
                                       + "magnitude=" + magnitude + ", tiltAngle=" + tiltAngle
                                       + ", orientationAngle=" + orientationAngle);
                           }
                       }
                   }
                }

                // Sum up the orientation confidence weights.
                // Detect an orientation change when the sum reaches 1.0.
                final float confidenceAmount = combinedConfidence * timeDeltaMS
                        / ORIENTATION_SETTLE_TIME_MS;
                for (int i = 0; i < 4; i++) {
                    if (i == proposedOrientation) {
                        mConfidence[i] += confidenceAmount;
                        if (mConfidence[i] >= 1.0f) {
                            mConfidence[i] = 1.0f;

                            if (i != mRotation) {
                                if (log) {
                                    Slog.v(TAG, "Orientation changed!  rotation=" + i);
                                }
                                mRotation = i;
                                orientationChanged = true;
                            }
                        }
                    } else {
                        mConfidence[i] -= confidenceAmount;
                        if (mConfidence[i] < 0.0f) {
                            mConfidence[i] = 0.0f;
                        }
                    }
                }
            }

            // Write final statistics about where we are in the orientation detection process.
            if (log) {
                Slog.v(TAG, "Result: rotation=" + mRotation
                        + ", confidence=["
                        + mConfidence[0] + ", "
                        + mConfidence[1] + ", "
                        + mConfidence[2] + ", "
                        + mConfidence[3] + "], timeDeltaMS=" + timeDeltaMS);
            }

            // Tell the listener.
            if (orientationChanged) {
                mOrientationListener.onOrientationChanged(mRotation);
            }
        }

        /**
         * Returns true if the tilt angle is acceptable for a proposed
         * orientation transition.
         */
        private boolean isTiltAngleAcceptable(int proposedOrientation,
                int tiltAngle) {
            return tiltAngle >= TILT_TOLERANCE[proposedOrientation][0]
                    && tiltAngle <= TILT_TOLERANCE[proposedOrientation][1];
        }

        /**
         * Returns true if the orientation angle is acceptable for a proposed
         * orientation transition.
         * This function takes into account the gap between adjacent orientations
         * for hysteresis.
         */
        private boolean isOrientationAngleAcceptable(int proposedOrientation,
                int orientationAngle) {
            final int currentOrientation = mRotation;

            // If there is no current rotation, then there is no gap.
            if (currentOrientation != ROTATION_UNKNOWN) {
                // If the proposed orientation is the same or is counter-clockwise adjacent,
                // then we set a lower bound on the orientation angle.
                // For example, if currentOrientation is ROTATION_0 and proposed is ROTATION_90,
                // then we want to check orientationAngle > 45 + GAP / 2.
                if (proposedOrientation == currentOrientation
                        || proposedOrientation == (currentOrientation + 1) % 4) {
                    int lowerBound = proposedOrientation * 90 - 45
                            + ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (proposedOrientation == 0) {
                        if (orientationAngle >= 315 && orientationAngle < lowerBound + 360) {
                            return false;
                        }
                    } else {
                        if (orientationAngle < lowerBound) {
                            return false;
                        }
                    }
                }

                // If the proposed orientation is the same or is clockwise adjacent,
                // then we set an upper bound on the orientation angle.
                // For example, if currentOrientation is ROTATION_0 and proposed is ROTATION_270,
                // then we want to check orientationAngle < 315 - GAP / 2.
                if (proposedOrientation == currentOrientation
                        || proposedOrientation == (currentOrientation + 3) % 4) {
                    int upperBound = proposedOrientation * 90 + 45
                            - ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (proposedOrientation == 0) {
                        if (orientationAngle <= 45 && orientationAngle > upperBound) {
                            return false;
                        }
                    } else {
                        if (orientationAngle > upperBound) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /**
         * Calculate an exponentially weighted confidence value in the range [0.0, 1.0].
         * The further the value is from the target, the more the confidence trends to 0.
         */
        private static float confidence(float value, float target, float scale) {
            return (float) Math.exp(-Math.abs(value - target) * scale);
        }

        /**
         * Calculate a scale factor for the confidence weight exponent.
         * The scale value is chosen such that confidence(value, target, scale) == 0.5
         * whenever abs(value - target) == cutoffDelta.
         */
        private static float confidenceScaleFromDelta(float cutoffDelta) {
            return (float) -Math.log(0.5) / cutoffDelta;
        }
    }
}
