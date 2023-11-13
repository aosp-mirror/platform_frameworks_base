/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.policy;

import static android.hardware.SensorManager.SENSOR_DELAY_NORMAL;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.policy.BookStylePreferredScreenCalculator.PreferredScreen.OUTER;
import static com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle.ANGLE_0;
import static com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle.ANGLE_0_TO_45;
import static com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle.ANGLE_45_TO_90;
import static com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle.ANGLE_90_TO_180;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.ArraySet;
import android.view.Display;
import android.view.Surface;

import com.android.server.policy.BookStylePreferredScreenCalculator.PreferredScreen;
import com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle;
import com.android.server.policy.BookStylePreferredScreenCalculator.StateTransition;
import com.android.server.policy.BookStyleClosedStatePredicate.ConditionSensorListener.SensorSubscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 'Closed' state predicate that takes into account the posture of the device
 * It accepts list of state transitions that control how the device moves between
 * device states.
 * See {@link BookStyleStateTransitions} for detailed description of the default behavior.
 */
public class BookStyleClosedStatePredicate implements Predicate<FoldableDeviceStateProvider>,
        DisplayManager.DisplayListener {

    private final BookStylePreferredScreenCalculator mClosedStateCalculator;
    private final Handler mHandler = new Handler();
    private final PostureEstimator mPostureEstimator;
    private final DisplayManager mDisplayManager;

    /**
     * Creates {@link BookStyleClosedStatePredicate}. It is expected that the device has a pair
     * of accelerometer sensors (one for each movable part of the device), see parameter
     * descriptions for the behaviour when these sensors are not available.
     * @param context context that could be used to get system services
     * @param updatesListener callback that will be executed whenever the predicate should be
     *                        checked again
     * @param leftAccelerometerSensor accelerometer sensor that is located in the half of the
     *                                device that has the outer screen, in case if this sensor is
     *                                not provided, tent/wedge mode will be detected only using
     *                                orientation sensor and screen rotation, so this mode won't
     *                                be accessible by putting the device on a flat surface
     * @param rightAccelerometerSensor accelerometer sensor that is located on the opposite side
     *                                 across the hinge from the previous accelerometer sensor,
     *                                 in case if this sensor is not provided, reverse wedge mode
     *                                 won't be detected, so the device will use closed state using
     *                                 constant angle when folding
     * @param stateTransitions definition of all possible state transitions, see
     *                         {@link BookStyleStateTransitions} for sample and more details
     */

    public BookStyleClosedStatePredicate(@NonNull Context context,
            @NonNull ClosedStateUpdatesListener updatesListener,
            @Nullable Sensor leftAccelerometerSensor, @Nullable Sensor rightAccelerometerSensor,
            @NonNull List<StateTransition> stateTransitions) {
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(this, mHandler);

        mClosedStateCalculator = new BookStylePreferredScreenCalculator(stateTransitions);

        final SensorManager sensorManager = context.getSystemService(SensorManager.class);
        final Sensor orientationSensor = sensorManager.getDefaultSensor(
                Sensor.TYPE_DEVICE_ORIENTATION);

        mPostureEstimator = new PostureEstimator(mHandler, sensorManager,
                leftAccelerometerSensor, rightAccelerometerSensor, orientationSensor,
                updatesListener::onClosedStateUpdated);
    }

    /**
     * Based on the current sensor readings and current state, returns true if the device should use
     * 'CLOSED' device state and false if it should not use 'CLOSED' state (e.g. could use half-open
     * or open states).
     */
    @Override
    public boolean test(FoldableDeviceStateProvider foldableDeviceStateProvider) {
        final HingeAngle hingeAngle = hingeAngleFromFloat(
                foldableDeviceStateProvider.getHingeAngle());

        mPostureEstimator.onDeviceClosedStatusChanged(hingeAngle == ANGLE_0);

        final PreferredScreen preferredScreen = mClosedStateCalculator.
                calculatePreferredScreen(hingeAngle, mPostureEstimator.isLikelyTentOrWedgeMode(),
                        mPostureEstimator.isLikelyReverseWedgeMode(hingeAngle));

        return preferredScreen == OUTER;
    }

    private HingeAngle hingeAngleFromFloat(float hingeAngle) {
        if (hingeAngle == 0f) {
            return ANGLE_0;
        } else if (hingeAngle < 45f) {
            return ANGLE_0_TO_45;
        } else if (hingeAngle < 90f) {
            return ANGLE_45_TO_90;
        } else {
            return ANGLE_90_TO_180;
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            final Display display = mDisplayManager.getDisplay(displayId);
            int displayState = display.getState();
            boolean isDisplayOn = displayState == Display.STATE_ON;
            mPostureEstimator.onDisplayPowerStatusChanged(isDisplayOn);
            mPostureEstimator.onDisplayRotationChanged(display.getRotation());
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {

    }

    @Override
    public void onDisplayRemoved(int displayId) {

    }

    public interface ClosedStateUpdatesListener {
        void onClosedStateUpdated();
    }

    /**
     * Estimates if the device is going to enter wedge/tent mode based on the sensor data
     */
    private static class PostureEstimator implements SensorEventListener {


        private static final int FLAT_INCLINATION_THRESHOLD_DEGREES = 8;

        /**
         * Alpha parameter of the accelerometer low pass filter: the lower the value, the less high
         * frequency noise it filter but reduces the latency.
         */
        private static final float GRAVITY_VECTOR_LOW_PASS_ALPHA_VALUE = 0.8f;


        @Nullable
        private final Sensor mLeftAccelerometerSensor;
        @Nullable
        private final Sensor mRightAccelerometerSensor;
        private final Sensor mOrientationSensor;
        private final Runnable mOnSensorUpdatedListener;

        private final ConditionSensorListener mConditionedSensorListener;

        @Nullable
        private float[] mRightGravityVector;

        @Nullable
        private float[] mLeftGravityVector;

        @Nullable
        private Integer mLastScreenRotation;

        @Nullable
        private SensorEvent mLastDeviceOrientationSensorEvent = null;

        private boolean mScreenTurnedOn = false;
        private boolean mDeviceClosed = false;

        public PostureEstimator(Handler handler, SensorManager sensorManager,
                @Nullable Sensor leftAccelerometerSensor, @Nullable Sensor rightAccelerometerSensor,
                Sensor orientationSensor, Runnable onSensorUpdated) {
            mLeftAccelerometerSensor = leftAccelerometerSensor;
            mRightAccelerometerSensor = rightAccelerometerSensor;
            mOrientationSensor = orientationSensor;

            mOnSensorUpdatedListener = onSensorUpdated;

            final List<SensorSubscription> sensorSubscriptions = new ArrayList<>();
            if (mLeftAccelerometerSensor != null) {
                sensorSubscriptions.add(new SensorSubscription(
                        mLeftAccelerometerSensor,
                        /* allowedToListen= */ () -> mScreenTurnedOn && !mDeviceClosed,
                        /* cleanup= */ () -> mLeftGravityVector = null));
            }

            if (mRightAccelerometerSensor != null) {
                sensorSubscriptions.add(new SensorSubscription(
                        mRightAccelerometerSensor,
                        /* allowedToListen= */ () -> mScreenTurnedOn,
                        /* cleanup= */ () -> mRightGravityVector = null));
            }

            sensorSubscriptions.add(new SensorSubscription(mOrientationSensor,
                    /* allowedToListen= */ () -> mScreenTurnedOn,
                    /* cleanup= */ () -> mLastDeviceOrientationSensorEvent = null));

            mConditionedSensorListener = new ConditionSensorListener(sensorManager, this, handler,
                    sensorSubscriptions);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor == mRightAccelerometerSensor) {
                if (mRightGravityVector == null) {
                    mRightGravityVector = new float[3];
                }
                setNewValueWithHighPassFilter(mRightGravityVector, event.values);

                final boolean isRightMostlyFlat = Objects.equals(
                        isGravityVectorMostlyFlat(mRightGravityVector), Boolean.TRUE);

                if (isRightMostlyFlat) {
                    // Reset orientation sensor when the device becomes flat
                    mLastDeviceOrientationSensorEvent = null;
                }
            } else if (event.sensor == mLeftAccelerometerSensor) {
                if (mLeftGravityVector == null) {
                    mLeftGravityVector = new float[3];
                }
                setNewValueWithHighPassFilter(mLeftGravityVector, event.values);
            } else if (event.sensor == mOrientationSensor) {
                mLastDeviceOrientationSensorEvent = event;
            }

            mOnSensorUpdatedListener.run();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }

        private void setNewValueWithHighPassFilter(float[] output, float[] newValues) {
            final float alpha = GRAVITY_VECTOR_LOW_PASS_ALPHA_VALUE;
            output[0] = alpha * output[0] + (1 - alpha) * newValues[0];
            output[1] = alpha * output[1] + (1 - alpha) * newValues[1];
            output[2] = alpha * output[2] + (1 - alpha) * newValues[2];
        }

        /**
         * Returns true if the phone likely in reverse wedge mode (when a foldable phone is lying
         * on the outer screen mostly flat to the ground)
         */
        public boolean isLikelyReverseWedgeMode(HingeAngle hingeAngle) {
            return hingeAngle != ANGLE_0 && Objects.equals(
                    isGravityVectorMostlyFlat(mLeftGravityVector), Boolean.TRUE);
        }

        /**
         * Returns true if the phone is likely in tent or wedge mode when unfolding. Tent mode
         * is detected by checking if the phone is in seascape position, screen is rotated to
         * landscape or seascape, or if the right side of the device is mostly flat.
         */
        public boolean isLikelyTentOrWedgeMode() {
            boolean isScreenLandscapeOrSeascape = Objects.equals(mLastScreenRotation,
                    Surface.ROTATION_270) || Objects.equals(mLastScreenRotation,
                    Surface.ROTATION_90);
            if (isScreenLandscapeOrSeascape) {
                return true;
            }

            boolean isRightMostlyFlat = Objects.equals(
                    isGravityVectorMostlyFlat(mRightGravityVector), Boolean.TRUE);
            if (isRightMostlyFlat) {
                return true;
            }

            boolean isSensorSeaScape = Objects.equals(getOrientationSensorRotation(),
                    Surface.ROTATION_270);
            if (isSensorSeaScape) {
                return true;
            }

            return false;
        }

        /**
         * Returns true if the passed gravity vector implies that the phone is mostly flat (the
         * vector is close to be perpendicular to the ground and has a positive Z component).
         * Returns null if there is no data from the sensor.
         */
        private Boolean isGravityVectorMostlyFlat(@Nullable float[] vector) {
            if (vector == null) return null;
            if (vector[0] == 0.0f && vector[1] == 0.0f && vector[2] == 0.0f) {
                // Likely we haven't received the actual data yet, treat it as no data
                return null;
            }

            double vectorMagnitude = Math.sqrt(
                    vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
            float normalizedGravityZ = (float) (vector[2] / vectorMagnitude);

            final int inclination = (int) Math.round(Math.toDegrees(Math.acos(normalizedGravityZ)));
            return inclination < FLAT_INCLINATION_THRESHOLD_DEGREES;
        }

        private Integer getOrientationSensorRotation() {
            if (mLastDeviceOrientationSensorEvent == null) return null;
            return (int) mLastDeviceOrientationSensorEvent.values[0];
        }

        /**
         * Called whenever display status changes, we use this signal to start/stop listening
         * to sensors when the display is off to save battery. Using display state instead of
         * general power state to reduce the time when sensors are on, we don't need to listen
         * to the extra sensors when the screen is off.
         */
        public void onDisplayPowerStatusChanged(boolean screenTurnedOn) {
            mScreenTurnedOn = screenTurnedOn;
            mConditionedSensorListener.updateListeningState();
        }

        /**
         * Called whenever we display rotation might have been updated
         * @param rotation new rotation
         */
        public void onDisplayRotationChanged(int rotation) {
            mLastScreenRotation = rotation;
        }

        /**
         * Called whenever foldable device becomes fully closed or opened
         */
        public void onDeviceClosedStatusChanged(boolean deviceClosed) {
            mDeviceClosed = deviceClosed;
            mConditionedSensorListener.updateListeningState();
        }
    }

    /**
     * Helper class that subscribes or unsubscribes from a sensor based on a condition specified
     * in {@link SensorSubscription}
     */
    static class ConditionSensorListener {
        private final List<SensorSubscription> mSensorSubscriptions;
        private final ArraySet<Sensor> mIsListening = new ArraySet<>();

        private final SensorManager mSensorManager;
        private final SensorEventListener mSensorEventListener;

        private final Handler mHandler;

        public ConditionSensorListener(SensorManager sensorManager,
                SensorEventListener sensorEventListener, Handler handler,
                List<SensorSubscription> sensorSubscriptions) {
            mSensorManager = sensorManager;
            mSensorEventListener = sensorEventListener;
            mSensorSubscriptions = sensorSubscriptions;
            mHandler = handler;
        }

        /**
         * Updates current listening state of the sensor based on the provided conditions
         */
        public void updateListeningState() {
            for (int i = 0; i < mSensorSubscriptions.size(); i++) {
                final SensorSubscription subscription = mSensorSubscriptions.get(i);
                final Sensor sensor = subscription.mSensor;

                final boolean shouldBeListening = subscription.mAllowedToListenSupplier.get();
                final boolean isListening = mIsListening.contains(sensor);
                final boolean shouldUpdateListening = isListening != shouldBeListening;

                if (shouldUpdateListening) {
                    if (shouldBeListening) {
                        mIsListening.add(sensor);
                        mSensorManager.registerListener(mSensorEventListener, sensor,
                                SENSOR_DELAY_NORMAL, mHandler);
                    } else {
                        mIsListening.remove(sensor);
                        mSensorManager.unregisterListener(mSensorEventListener, sensor);
                        subscription.mOnUnsubscribe.run();
                    }
                }
            }
        }

        /**
         * Represents a configuration of a single sensor subscription
         */
        public static class SensorSubscription {
            private final Sensor mSensor;
            private final Supplier<Boolean> mAllowedToListenSupplier;
            private final Runnable mOnUnsubscribe;

            /**
             * @param sensor sensor to listen to
             * @param allowedToListen return true when it is allowed to listen to the sensor
             * @param cleanup a runnable that will be closed just before unsubscribing from the
             *                sensor
             */

            public SensorSubscription(Sensor sensor, Supplier<Boolean> allowedToListen,
                    Runnable cleanup) {
                mSensor = sensor;
                mAllowedToListenSupplier = allowedToListen;
                mOnUnsubscribe = cleanup;
            }
        }
    }
}
