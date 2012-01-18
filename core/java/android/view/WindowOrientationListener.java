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
import android.util.FloatMath;
import android.util.Log;
import android.util.Slog;

/**
 * A special helper class used by the WindowManager
 * for receiving notifications from the SensorManager when
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

    private static final boolean USE_GRAVITY_SENSOR = false;

    private SensorManager mSensorManager;
    private boolean mEnabled;
    private int mRate;
    private Sensor mSensor;
    private SensorEventListenerImpl mSensorEventListener;
    boolean mLogEnabled;
    int mCurrentRotation = -1;

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
        mSensor = mSensorManager.getDefaultSensor(USE_GRAVITY_SENSOR
                ? Sensor.TYPE_GRAVITY : Sensor.TYPE_ACCELEROMETER);
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
     * Sets the current rotation.
     *
     * @param rotation The current rotation.
     */
    public void setCurrentRotation(int rotation) {
        mCurrentRotation = rotation;
    }

    /**
     * Gets the proposed rotation.
     *
     * This method only returns a rotation if the orientation listener is certain
     * of its proposal.  If the rotation is indeterminate, returns -1.
     *
     * @return The proposed rotation, or -1 if unknown.
     */
    public int getProposedRotation() {
        if (mEnabled) {
            return mSensorEventListener.getProposedRotation();
        }
        return -1;
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
     * This method is called whenever the orientation becomes certain of an orientation.
     * It is called each time the orientation determination transitions from being
     * uncertain to being certain again, even if it is the same orientation as before.
     *
     * @param rotation The new orientation of the device, one of the Surface.ROTATION_* constants.
     * @see Surface
     */
    public abstract void onProposedRotationChanged(int rotation);

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
     *    filtering early, we can eliminate most spurious high-frequency impulses due to noise.
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
     *  - Wait for the device to settle for a little bit.  Once that happens, issue the
     *    new orientation proposal.
     *
     * Details are explained inline.
     *
     * See http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization for
     * signal processing background.
     */
    static final class SensorEventListenerImpl implements SensorEventListener {
        // We work with all angles in degrees in this class.
        private static final float RADIANS_TO_DEGREES = (float) (180 / Math.PI);

        // Number of nanoseconds per millisecond.
        private static final long NANOS_PER_MS = 1000000;

        // Indices into SensorEvent.values for the accelerometer sensor.
        private static final int ACCELEROMETER_DATA_X = 0;
        private static final int ACCELEROMETER_DATA_Y = 1;
        private static final int ACCELEROMETER_DATA_Z = 2;

        private final WindowOrientationListener mOrientationListener;

        // The minimum amount of time that a predicted rotation must be stable before it
        // is accepted as a valid rotation proposal.  This value can be quite small because
        // the low-pass filter already suppresses most of the noise so we're really just
        // looking for quick confirmation that the last few samples are in agreement as to
        // the desired orientation.
        private static final long PROPOSAL_SETTLE_TIME_NANOS = 40 * NANOS_PER_MS;

        // The minimum amount of time that must have elapsed since the device last exited
        // the flat state (time since it was picked up) before the proposed rotation
        // can change.
        private static final long PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS = 500 * NANOS_PER_MS;

        // The mininum amount of time that must have elapsed since the device stopped
        // swinging (time since device appeared to be in the process of being put down
        // or put away into a pocket) before the proposed rotation can change.
        private static final long PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS = 300 * NANOS_PER_MS;

        // If the tilt angle remains greater than the specified angle for a minimum of
        // the specified time, then the device is deemed to be lying flat
        // (just chillin' on a table).
        private static final float FLAT_ANGLE = 75;
        private static final long FLAT_TIME_NANOS = 1000 * NANOS_PER_MS;

        // If the tilt angle has increased by at least delta degrees within the specified amount
        // of time, then the device is deemed to be swinging away from the user
        // down towards flat (tilt = 90).
        private static final float SWING_AWAY_ANGLE_DELTA = 20;
        private static final long SWING_TIME_NANOS = 300 * NANOS_PER_MS;

        // The maximum sample inter-arrival time in milliseconds.
        // If the acceleration samples are further apart than this amount in time, we reset the
        // state of the low-pass filter and orientation properties.  This helps to handle
        // boundary conditions when the device is turned on, wakes from suspend or there is
        // a significant gap in samples.
        private static final long MAX_FILTER_DELTA_TIME_NANOS = 1000 * NANOS_PER_MS;

        // The acceleration filter time constant.
        //
        // This time constant is used to tune the acceleration filter such that
        // impulses and vibrational noise (think car dock) is suppressed before we
        // try to calculate the tilt and orientation angles.
        //
        // The filter time constant is related to the filter cutoff frequency, which is the
        // frequency at which signals are attenuated by 3dB (half the passband power).
        // Each successive octave beyond this frequency is attenuated by an additional 6dB.
        //
        // Given a time constant t in seconds, the filter cutoff frequency Fc in Hertz
        // is given by Fc = 1 / (2pi * t).
        //
        // The higher the time constant, the lower the cutoff frequency, so more noise
        // will be suppressed.
        //
        // Filtering adds latency proportional the time constant (inversely proportional
        // to the cutoff frequency) so we don't want to make the time constant too
        // large or we can lose responsiveness.  Likewise we don't want to make it too
        // small or we do a poor job suppressing acceleration spikes.
        // Empirically, 100ms seems to be too small and 500ms is too large.
        private static final float FILTER_TIME_CONSTANT_MS = 200.0f;

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
                SensorManager.STANDARD_GRAVITY * 0.3f;
        private static final float MAX_ACCELERATION_MAGNITUDE =
            SensorManager.STANDARD_GRAVITY * 1.25f;

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
            /* ROTATION_0   */ { -25, 70 },
            /* ROTATION_90  */ { -25, 65 },
            /* ROTATION_180 */ { -25, 60 },
            /* ROTATION_270 */ { -25, 65 }
        };

        // The gap angle in degrees between adjacent orientation angles for hysteresis.
        // This creates a "dead zone" between the current orientation and a proposed
        // adjacent orientation.  No orientation proposal is made when the orientation
        // angle is within the gap between the current orientation and the adjacent
        // orientation.
        private static final int ADJACENT_ORIENTATION_ANGLE_GAP = 45;

        // Timestamp and value of the last accelerometer sample.
        private long mLastFilteredTimestampNanos;
        private float mLastFilteredX, mLastFilteredY, mLastFilteredZ;

        // The last proposed rotation, -1 if unknown.
        private int mProposedRotation;

        // Value of the current predicted rotation, -1 if unknown.
        private int mPredictedRotation;

        // Timestamp of when the predicted rotation most recently changed.
        private long mPredictedRotationTimestampNanos;

        // Timestamp when the device last appeared to be flat for sure (the flat delay elapsed).
        private long mFlatTimestampNanos;

        // Timestamp when the device last appeared to be swinging.
        private long mSwingTimestampNanos;

        // History of observed tilt angles.
        private static final int TILT_HISTORY_SIZE = 40;
        private float[] mTiltHistory = new float[TILT_HISTORY_SIZE];
        private long[] mTiltHistoryTimestampNanos = new long[TILT_HISTORY_SIZE];
        private int mTiltHistoryIndex;

        public SensorEventListenerImpl(WindowOrientationListener orientationListener) {
            mOrientationListener = orientationListener;
            reset();
        }

        public int getProposedRotation() {
            return mProposedRotation;
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
                Slog.v(TAG, "Raw acceleration vector: "
                        + "x=" + x + ", y=" + y + ", z=" + z
                        + ", magnitude=" + FloatMath.sqrt(x * x + y * y + z * z));
            }

            // Apply a low-pass filter to the acceleration up vector in cartesian space.
            // Reset the orientation listener state if the samples are too far apart in time
            // or when we see values of (0, 0, 0) which indicates that we polled the
            // accelerometer too soon after turning it on and we don't have any data yet.
            final long now = event.timestamp;
            final long then = mLastFilteredTimestampNanos;
            final float timeDeltaMS = (now - then) * 0.000001f;
            final boolean skipSample;
            if (now < then
                    || now > then + MAX_FILTER_DELTA_TIME_NANOS
                    || (x == 0 && y == 0 && z == 0)) {
                if (log) {
                    Slog.v(TAG, "Resetting orientation listener.");
                }
                reset();
                skipSample = true;
            } else {
                final float alpha = timeDeltaMS / (FILTER_TIME_CONSTANT_MS + timeDeltaMS);
                x = alpha * (x - mLastFilteredX) + mLastFilteredX;
                y = alpha * (y - mLastFilteredY) + mLastFilteredY;
                z = alpha * (z - mLastFilteredZ) + mLastFilteredZ;
                if (log) {
                    Slog.v(TAG, "Filtered acceleration vector: "
                            + "x=" + x + ", y=" + y + ", z=" + z
                            + ", magnitude=" + FloatMath.sqrt(x * x + y * y + z * z));
                }
                skipSample = false;
            }
            mLastFilteredTimestampNanos = now;
            mLastFilteredX = x;
            mLastFilteredY = y;
            mLastFilteredZ = z;

            boolean isFlat = false;
            boolean isSwinging = false;
            if (!skipSample) {
                // Calculate the magnitude of the acceleration vector.
                final float magnitude = FloatMath.sqrt(x * x + y * y + z * z);
                if (magnitude < MIN_ACCELERATION_MAGNITUDE
                        || magnitude > MAX_ACCELERATION_MAGNITUDE) {
                    if (log) {
                        Slog.v(TAG, "Ignoring sensor data, magnitude out of range.");
                    }
                    clearPredictedRotation();
                } else {
                    // Calculate the tilt angle.
                    // This is the angle between the up vector and the x-y plane (the plane of
                    // the screen) in a range of [-90, 90] degrees.
                    //   -90 degrees: screen horizontal and facing the ground (overhead)
                    //     0 degrees: screen vertical
                    //    90 degrees: screen horizontal and facing the sky (on table)
                    final int tiltAngle = (int) Math.round(
                            Math.asin(z / magnitude) * RADIANS_TO_DEGREES);

                    // Determine whether the device appears to be flat or swinging.
                    if (isFlat(now)) {
                        isFlat = true;
                        mFlatTimestampNanos = now;
                    }
                    if (isSwinging(now, tiltAngle)) {
                        isSwinging = true;
                        mSwingTimestampNanos = now;
                    }
                    addTiltHistoryEntry(now, tiltAngle);

                    // If the tilt angle is too close to horizontal then we cannot determine
                    // the orientation angle of the screen.
                    if (Math.abs(tiltAngle) > MAX_TILT) {
                        if (log) {
                            Slog.v(TAG, "Ignoring sensor data, tilt angle too high: "
                                    + "tiltAngle=" + tiltAngle);
                        }
                        clearPredictedRotation();
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

                        // Find the nearest rotation.
                        int nearestRotation = (orientationAngle + 45) / 90;
                        if (nearestRotation == 4) {
                            nearestRotation = 0;
                        }

                        // Determine the predicted orientation.
                        if (isTiltAngleAcceptable(nearestRotation, tiltAngle)
                                && isOrientationAngleAcceptable(nearestRotation,
                                        orientationAngle)) {
                            updatePredictedRotation(now, nearestRotation);
                            if (log) {
                                Slog.v(TAG, "Predicted: "
                                        + "tiltAngle=" + tiltAngle
                                        + ", orientationAngle=" + orientationAngle
                                        + ", predictedRotation=" + mPredictedRotation
                                        + ", predictedRotationAgeMS="
                                                + ((now - mPredictedRotationTimestampNanos)
                                                        * 0.000001f));
                            }
                        } else {
                            if (log) {
                                Slog.v(TAG, "Ignoring sensor data, no predicted rotation: "
                                        + "tiltAngle=" + tiltAngle
                                        + ", orientationAngle=" + orientationAngle);
                            }
                            clearPredictedRotation();
                        }
                    }
                }
            }

            // Determine new proposed rotation.
            final int oldProposedRotation = mProposedRotation;
            if (mPredictedRotation < 0 || isPredictedRotationAcceptable(now)) {
                mProposedRotation = mPredictedRotation;
            }

            // Write final statistics about where we are in the orientation detection process.
            if (log) {
                Slog.v(TAG, "Result: currentRotation=" + mOrientationListener.mCurrentRotation
                        + ", proposedRotation=" + mProposedRotation
                        + ", predictedRotation=" + mPredictedRotation
                        + ", timeDeltaMS=" + timeDeltaMS
                        + ", isFlat=" + isFlat
                        + ", isSwinging=" + isSwinging
                        + ", timeUntilSettledMS=" + remainingMS(now,
                                mPredictedRotationTimestampNanos + PROPOSAL_SETTLE_TIME_NANOS)
                        + ", timeUntilFlatDelayExpiredMS=" + remainingMS(now,
                                mFlatTimestampNanos + PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS)
                        + ", timeUntilSwingDelayExpiredMS=" + remainingMS(now,
                                mSwingTimestampNanos + PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS));
            }

            // Tell the listener.
            if (mProposedRotation != oldProposedRotation && mProposedRotation >= 0) {
                if (log) {
                    Slog.v(TAG, "Proposed rotation changed!  proposedRotation=" + mProposedRotation
                            + ", oldProposedRotation=" + oldProposedRotation);
                }
                mOrientationListener.onProposedRotationChanged(mProposedRotation);
            }
        }

        /**
         * Returns true if the tilt angle is acceptable for a given predicted rotation.
         */
        private boolean isTiltAngleAcceptable(int rotation, int tiltAngle) {
            return tiltAngle >= TILT_TOLERANCE[rotation][0]
                    && tiltAngle <= TILT_TOLERANCE[rotation][1];
        }

        /**
         * Returns true if the orientation angle is acceptable for a given predicted rotation.
         *
         * This function takes into account the gap between adjacent orientations
         * for hysteresis.
         */
        private boolean isOrientationAngleAcceptable(int rotation, int orientationAngle) {
            // If there is no current rotation, then there is no gap.
            // The gap is used only to introduce hysteresis among advertised orientation
            // changes to avoid flapping.
            final int currentRotation = mOrientationListener.mCurrentRotation;
            if (currentRotation >= 0) {
                // If the specified rotation is the same or is counter-clockwise adjacent
                // to the current rotation, then we set a lower bound on the orientation angle.
                // For example, if currentRotation is ROTATION_0 and proposed is ROTATION_90,
                // then we want to check orientationAngle > 45 + GAP / 2.
                if (rotation == currentRotation
                        || rotation == (currentRotation + 1) % 4) {
                    int lowerBound = rotation * 90 - 45
                            + ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (rotation == 0) {
                        if (orientationAngle >= 315 && orientationAngle < lowerBound + 360) {
                            return false;
                        }
                    } else {
                        if (orientationAngle < lowerBound) {
                            return false;
                        }
                    }
                }

                // If the specified rotation is the same or is clockwise adjacent,
                // then we set an upper bound on the orientation angle.
                // For example, if currentRotation is ROTATION_0 and rotation is ROTATION_270,
                // then we want to check orientationAngle < 315 - GAP / 2.
                if (rotation == currentRotation
                        || rotation == (currentRotation + 3) % 4) {
                    int upperBound = rotation * 90 + 45
                            - ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (rotation == 0) {
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
         * Returns true if the predicted rotation is ready to be advertised as a
         * proposed rotation.
         */
        private boolean isPredictedRotationAcceptable(long now) {
            // The predicted rotation must have settled long enough.
            if (now < mPredictedRotationTimestampNanos + PROPOSAL_SETTLE_TIME_NANOS) {
                return false;
            }

            // The last flat state (time since picked up) must have been sufficiently long ago.
            if (now < mFlatTimestampNanos + PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS) {
                return false;
            }

            // The last swing state (time since last movement to put down) must have been
            // sufficiently long ago.
            if (now < mSwingTimestampNanos + PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS) {
                return false;
            }

            // Looks good!
            return true;
        }

        private void reset() {
            mLastFilteredTimestampNanos = Long.MIN_VALUE;
            mProposedRotation = -1;
            mFlatTimestampNanos = Long.MIN_VALUE;
            mSwingTimestampNanos = Long.MIN_VALUE;
            clearPredictedRotation();
            clearTiltHistory();
        }

        private void clearPredictedRotation() {
            mPredictedRotation = -1;
            mPredictedRotationTimestampNanos = Long.MIN_VALUE;
        }

        private void updatePredictedRotation(long now, int rotation) {
            if (mPredictedRotation != rotation) {
                mPredictedRotation = rotation;
                mPredictedRotationTimestampNanos = now;
            }
        }

        private void clearTiltHistory() {
            mTiltHistoryTimestampNanos[0] = Long.MIN_VALUE;
            mTiltHistoryIndex = 1;
        }

        private void addTiltHistoryEntry(long now, float tilt) {
            mTiltHistory[mTiltHistoryIndex] = tilt;
            mTiltHistoryTimestampNanos[mTiltHistoryIndex] = now;
            mTiltHistoryIndex = (mTiltHistoryIndex + 1) % TILT_HISTORY_SIZE;
            mTiltHistoryTimestampNanos[mTiltHistoryIndex] = Long.MIN_VALUE;
        }

        private boolean isFlat(long now) {
            for (int i = mTiltHistoryIndex; (i = nextTiltHistoryIndex(i)) >= 0; ) {
                if (mTiltHistory[i] < FLAT_ANGLE) {
                    break;
                }
                if (mTiltHistoryTimestampNanos[i] + FLAT_TIME_NANOS <= now) {
                    // Tilt has remained greater than FLAT_TILT_ANGLE for FLAT_TIME_NANOS.
                    return true;
                }
            }
            return false;
        }

        private boolean isSwinging(long now, float tilt) {
            for (int i = mTiltHistoryIndex; (i = nextTiltHistoryIndex(i)) >= 0; ) {
                if (mTiltHistoryTimestampNanos[i] + SWING_TIME_NANOS < now) {
                    break;
                }
                if (mTiltHistory[i] + SWING_AWAY_ANGLE_DELTA <= tilt) {
                    // Tilted away by SWING_AWAY_ANGLE_DELTA within SWING_TIME_NANOS.
                    return true;
                }
            }
            return false;
        }

        private int nextTiltHistoryIndex(int index) {
            index = (index == 0 ? TILT_HISTORY_SIZE : index) - 1;
            return mTiltHistoryTimestampNanos[index] != Long.MIN_VALUE ? index : -1;
        }

        private static float remainingMS(long now, long until) {
            return now >= until ? 0 : (until - now) * 0.000001f;
        }
    }
}
