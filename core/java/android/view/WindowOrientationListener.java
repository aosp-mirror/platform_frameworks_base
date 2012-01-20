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
     *  - Wait for the device to settle for a little bit.  Once that happens, issue the
     *    new orientation proposal.
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

        private final WindowOrientationListener mOrientationListener;

        /* State for first order low-pass filtering of accelerometer data.
         * See http://en.wikipedia.org/wiki/Low-pass_filter#Discrete-time_realization for
         * signal processing background.
         */

        private long mLastTimestamp = Long.MAX_VALUE; // in nanoseconds
        private float mLastFilteredX, mLastFilteredY, mLastFilteredZ;

        // The current proposal.  We wait for the proposal to be stable for a
        // certain amount of time before accepting it.
        //
        // The basic idea is to ignore intermediate poses of the device while the
        // user is picking up, putting down or turning the device.
        private long mProposalTime;
        private int mProposalRotation;
        private long mProposalAgeMS;
        private boolean mProposalSettled;

        // A historical trace of tilt and orientation angles.  Used to determine whether
        // the device posture has settled down.
        private static final int HISTORY_SIZE = 20;
        private int mHistoryIndex; // index of most recent sample
        private int mHistoryLength; // length of historical trace
        private final long[] mHistoryTimestampMS = new long[HISTORY_SIZE];
        private final float[] mHistoryMagnitudes = new float[HISTORY_SIZE];
        private final int[] mHistoryTiltAngles = new int[HISTORY_SIZE];
        private final int[] mHistoryOrientationAngles = new int[HISTORY_SIZE];

        // The maximum sample inter-arrival time in milliseconds.
        // If the acceleration samples are further apart than this amount in time, we reset the
        // state of the low-pass filter and orientation properties.  This helps to handle
        // boundary conditions when the device is turned on, wakes from suspend or there is
        // a significant gap in samples.
        private static final float MAX_FILTER_DELTA_TIME_MS = 1000;

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
        // large or we can lose responsiveness.
        private static final float FILTER_TIME_CONSTANT_MS = 100.0f;

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

        // The number of milliseconds for which the device posture must be stable
        // before we perform an orientation change.  If the device appears to be rotating
        // (being picked up, put down) then we keep waiting until it settles.
        private static final int SETTLE_TIME_MIN_MS = 200;

        // The maximum number of milliseconds to wait for the posture to settle before
        // accepting the current proposal regardless.
        private static final int SETTLE_TIME_MAX_MS = 500;

        // The maximum change in magnitude that can occur during the settle time.
        // Tuning this constant particularly helps to filter out situations where the
        // device is being picked up or put down by the user.
        private static final float SETTLE_MAGNITUDE_MAX_DELTA =
                SensorManager.STANDARD_GRAVITY * 0.2f;

        // The maximum change in tilt angle that can occur during the settle time.
        private static final int SETTLE_TILT_ANGLE_MAX_DELTA = 8;

        // The maximum change in orientation angle that can occur during the settle time.
        private static final int SETTLE_ORIENTATION_ANGLE_MAX_DELTA = 8;

        public SensorEventListenerImpl(WindowOrientationListener orientationListener) {
            mOrientationListener = orientationListener;
        }

        public int getProposedRotation() {
            return mProposalSettled ? mProposalRotation : -1;
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
            final long now = event.timestamp;
            final float timeDeltaMS = (now - mLastTimestamp) * 0.000001f;
            boolean skipSample;
            if (timeDeltaMS <= 0 || timeDeltaMS > MAX_FILTER_DELTA_TIME_MS
                    || (x == 0 && y == 0 && z == 0)) {
                if (log) {
                    Slog.v(TAG, "Resetting orientation listener.");
                }
                clearProposal();
                skipSample = true;
            } else {
                final float alpha = timeDeltaMS / (FILTER_TIME_CONSTANT_MS + timeDeltaMS);
                x = alpha * (x - mLastFilteredX) + mLastFilteredX;
                y = alpha * (y - mLastFilteredY) + mLastFilteredY;
                z = alpha * (z - mLastFilteredZ) + mLastFilteredZ;
                if (log) {
                    Slog.v(TAG, "Filtered acceleration vector: " +
                            "x=" + x + ", y=" + y + ", z=" + z);
                }
                skipSample = false;
            }
            mLastTimestamp = now;
            mLastFilteredX = x;
            mLastFilteredY = y;
            mLastFilteredZ = z;

            final int oldProposedRotation = getProposedRotation();
            if (!skipSample) {
                // Calculate the magnitude of the acceleration vector.
                final float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
                if (magnitude < MIN_ACCELERATION_MAGNITUDE
                        || magnitude > MAX_ACCELERATION_MAGNITUDE) {
                    if (log) {
                        Slog.v(TAG, "Ignoring sensor data, magnitude out of range: "
                                + "magnitude=" + magnitude);
                    }
                    clearProposal();
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
                        clearProposal();
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

                        // Determine the proposed orientation.
                        if (!isTiltAngleAcceptable(nearestRotation, tiltAngle)
                                || !isOrientationAngleAcceptable(nearestRotation,
                                        orientationAngle)) {
                            if (log) {
                                Slog.v(TAG, "Ignoring sensor data, no proposal: "
                                        + "magnitude=" + magnitude + ", tiltAngle=" + tiltAngle
                                        + ", orientationAngle=" + orientationAngle);
                            }
                            clearProposal();
                        } else {
                            if (log) {
                                Slog.v(TAG, "Proposal: "
                                        + "magnitude=" + magnitude
                                        + ", tiltAngle=" + tiltAngle
                                        + ", orientationAngle=" + orientationAngle
                                        + ", proposalRotation=" + mProposalRotation);
                            }
                            updateProposal(nearestRotation, now / 1000000L,
                                    magnitude, tiltAngle, orientationAngle);
                        }
                    }
                }
            }

            // Write final statistics about where we are in the orientation detection process.
            final int proposedRotation = getProposedRotation();
            if (log) {
                final float proposalConfidence = Math.min(
                        mProposalAgeMS * 1.0f / SETTLE_TIME_MIN_MS, 1.0f);
                Slog.v(TAG, "Result: currentRotation=" + mOrientationListener.mCurrentRotation
                        + ", proposedRotation=" + proposedRotation
                        + ", timeDeltaMS=" + timeDeltaMS
                        + ", proposalRotation=" + mProposalRotation
                        + ", proposalAgeMS=" + mProposalAgeMS
                        + ", proposalConfidence=" + proposalConfidence);
            }

            // Tell the listener.
            if (proposedRotation != oldProposedRotation && proposedRotation >= 0) {
                if (log) {
                    Slog.v(TAG, "Proposed rotation changed!  proposedRotation=" + proposedRotation
                            + ", oldProposedRotation=" + oldProposedRotation);
                }
                mOrientationListener.onProposedRotationChanged(proposedRotation);
            }
        }

        /**
         * Returns true if the tilt angle is acceptable for a proposed
         * orientation transition.
         */
        private boolean isTiltAngleAcceptable(int proposedRotation,
                int tiltAngle) {
            return tiltAngle >= TILT_TOLERANCE[proposedRotation][0]
                    && tiltAngle <= TILT_TOLERANCE[proposedRotation][1];
        }

        /**
         * Returns true if the orientation angle is acceptable for a proposed
         * orientation transition.
         *
         * This function takes into account the gap between adjacent orientations
         * for hysteresis.
         */
        private boolean isOrientationAngleAcceptable(int proposedRotation, int orientationAngle) {
            // If there is no current rotation, then there is no gap.
            // The gap is used only to introduce hysteresis among advertised orientation
            // changes to avoid flapping.
            final int currentRotation = mOrientationListener.mCurrentRotation;
            if (currentRotation >= 0) {
                // If the proposed rotation is the same or is counter-clockwise adjacent,
                // then we set a lower bound on the orientation angle.
                // For example, if currentRotation is ROTATION_0 and proposed is ROTATION_90,
                // then we want to check orientationAngle > 45 + GAP / 2.
                if (proposedRotation == currentRotation
                        || proposedRotation == (currentRotation + 1) % 4) {
                    int lowerBound = proposedRotation * 90 - 45
                            + ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (proposedRotation == 0) {
                        if (orientationAngle >= 315 && orientationAngle < lowerBound + 360) {
                            return false;
                        }
                    } else {
                        if (orientationAngle < lowerBound) {
                            return false;
                        }
                    }
                }

                // If the proposed rotation is the same or is clockwise adjacent,
                // then we set an upper bound on the orientation angle.
                // For example, if currentRotation is ROTATION_0 and proposed is ROTATION_270,
                // then we want to check orientationAngle < 315 - GAP / 2.
                if (proposedRotation == currentRotation
                        || proposedRotation == (currentRotation + 3) % 4) {
                    int upperBound = proposedRotation * 90 + 45
                            - ADJACENT_ORIENTATION_ANGLE_GAP / 2;
                    if (proposedRotation == 0) {
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

        private void clearProposal() {
            mProposalRotation = -1;
            mProposalAgeMS = 0;
            mProposalSettled = false;
        }

        private void updateProposal(int rotation, long timestampMS,
                float magnitude, int tiltAngle, int orientationAngle) {
            if (mProposalRotation != rotation) {
                mProposalTime = timestampMS;
                mProposalRotation = rotation;
                mHistoryIndex = 0;
                mHistoryLength = 0;
            }

            final int index = mHistoryIndex;
            mHistoryTimestampMS[index] = timestampMS;
            mHistoryMagnitudes[index] = magnitude;
            mHistoryTiltAngles[index] = tiltAngle;
            mHistoryOrientationAngles[index] = orientationAngle;
            mHistoryIndex = (index + 1) % HISTORY_SIZE;
            if (mHistoryLength < HISTORY_SIZE) {
                mHistoryLength += 1;
            }

            long age = 0;
            for (int i = 1; i < mHistoryLength; i++) {
                final int olderIndex = (index + HISTORY_SIZE - i) % HISTORY_SIZE;
                if (Math.abs(mHistoryMagnitudes[olderIndex] - magnitude)
                        > SETTLE_MAGNITUDE_MAX_DELTA) {
                    break;
                }
                if (angleAbsoluteDelta(mHistoryTiltAngles[olderIndex],
                        tiltAngle) > SETTLE_TILT_ANGLE_MAX_DELTA) {
                    break;
                }
                if (angleAbsoluteDelta(mHistoryOrientationAngles[olderIndex],
                        orientationAngle) > SETTLE_ORIENTATION_ANGLE_MAX_DELTA) {
                    break;
                }
                age = timestampMS - mHistoryTimestampMS[olderIndex];
                if (age >= SETTLE_TIME_MIN_MS) {
                    break;
                }
            }
            mProposalAgeMS = age;
            if (age >= SETTLE_TIME_MIN_MS
                    || timestampMS - mProposalTime >= SETTLE_TIME_MAX_MS) {
                mProposalSettled = true;
            } else {
                mProposalSettled = false;
            }
        }

        private static int angleAbsoluteDelta(int a, int b) {
            int delta = Math.abs(a - b);
            if (delta > 180) {
                delta = 360 - delta;
            }
            return delta;
        }
    }
}
