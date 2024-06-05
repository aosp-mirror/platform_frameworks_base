/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.display.utils.SensorUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing the keyboard
 * backlight based on ambient light sensor.
 */
final class AmbientKeyboardBacklightController implements DisplayManager.DisplayListener,
        SensorEventListener {

    private static final String TAG = "KbdBacklightController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KbdBacklightController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Number of light sensor responses required to overcome temporal hysteresis.
    @VisibleForTesting
    public static final int HYSTERESIS_THRESHOLD = 2;

    private static final int MSG_BRIGHTNESS_CALLBACK = 0;
    private static final int MSG_SETUP_DISPLAY_AND_SENSOR = 1;

    private static final Object sAmbientControllerLock = new Object();

    private final Context mContext;
    private final Handler mHandler;

    @Nullable
    @GuardedBy("sAmbientControllerLock")
    private Sensor mLightSensor;
    @GuardedBy("sAmbientControllerLock")
    private String mCurrentDefaultDisplayUniqueId;

    // List of currently registered ambient backlight listeners
    @GuardedBy("sAmbientControllerLock")
    private final List<AmbientKeyboardBacklightListener> mAmbientKeyboardBacklightListeners =
            new ArrayList<>();

    private BrightnessStep[] mBrightnessSteps;
    private int mCurrentBrightnessStepIndex;
    private HysteresisState mHysteresisState;
    private int mHysteresisCount = 0;
    private float mSmoothingConstant;
    private int mSmoothedLux;
    private int mSmoothedLuxAtLastAdjustment;

    private enum HysteresisState {
        // The most-recent mSmoothedLux matched mSmoothedLuxAtLastAdjustment.
        STABLE,
        // The most-recent mSmoothedLux was less than mSmoothedLuxAtLastAdjustment.
        DECREASING,
        // The most-recent mSmoothedLux was greater than mSmoothedLuxAtLastAdjustment.
        INCREASING,
        // The brightness should be adjusted immediately after the next sensor reading.
        IMMEDIATE,
    }

    AmbientKeyboardBacklightController(Context context, Looper looper) {
        mContext = context;
        mHandler = new Handler(looper, this::handleMessage);
        initConfiguration();
    }

    public void systemRunning() {
        mHandler.sendEmptyMessage(MSG_SETUP_DISPLAY_AND_SENSOR);
        DisplayManager displayManager = Objects.requireNonNull(
                mContext.getSystemService(DisplayManager.class));
        displayManager.registerDisplayListener(this, mHandler);
    }

    public void registerAmbientBacklightListener(AmbientKeyboardBacklightListener listener) {
        synchronized (sAmbientControllerLock) {
            if (mAmbientKeyboardBacklightListeners.contains(listener)) {
                throw new IllegalStateException(
                        "AmbientKeyboardBacklightListener was already registered, listener = "
                                + listener);
            }
            if (mAmbientKeyboardBacklightListeners.isEmpty()) {
                // Add sensor listener when we add the first ambient backlight listener.
                addSensorListener(mLightSensor);
            }
            mAmbientKeyboardBacklightListeners.add(listener);
        }
    }

    public void unregisterAmbientBacklightListener(AmbientKeyboardBacklightListener listener) {
        synchronized (sAmbientControllerLock) {
            if (!mAmbientKeyboardBacklightListeners.contains(listener)) {
                throw new IllegalStateException(
                        "AmbientKeyboardBacklightListener was never registered, listener = "
                                + listener);
            }
            mAmbientKeyboardBacklightListeners.remove(listener);
            if (mAmbientKeyboardBacklightListeners.isEmpty()) {
                removeSensorListener(mLightSensor);
            }
        }
    }

    private void sendBrightnessAdjustment(int brightnessValue) {
        Message msg = Message.obtain(mHandler, MSG_BRIGHTNESS_CALLBACK, brightnessValue);
        mHandler.sendMessage(msg);
    }

    @MainThread
    private void handleBrightnessCallback(int brightnessValue) {
        synchronized (sAmbientControllerLock) {
            for (AmbientKeyboardBacklightListener listener : mAmbientKeyboardBacklightListeners) {
                listener.onKeyboardBacklightValueChanged(brightnessValue);
            }
        }
    }

    @MainThread
    private void handleAmbientLuxChange(float rawLux) {
        if (rawLux < 0) {
            Slog.w(TAG, "Light sensor doesn't have valid value");
            return;
        }
        updateSmoothedLux(rawLux);

        if (mHysteresisState != HysteresisState.IMMEDIATE
                && mSmoothedLux == mSmoothedLuxAtLastAdjustment) {
            mHysteresisState = HysteresisState.STABLE;
            return;
        }

        int newStepIndex = Math.max(0, mCurrentBrightnessStepIndex);
        int numSteps = mBrightnessSteps.length;

        if (mSmoothedLux > mSmoothedLuxAtLastAdjustment) {
            if (mHysteresisState != HysteresisState.IMMEDIATE
                    && mHysteresisState != HysteresisState.INCREASING) {
                if (DEBUG) {
                    Slog.d(TAG, "ALS transitioned to brightness increasing state");
                }
                mHysteresisState = HysteresisState.INCREASING;
                mHysteresisCount = 0;
            }
            for (; newStepIndex < numSteps; newStepIndex++) {
                if (mSmoothedLux < mBrightnessSteps[newStepIndex].mIncreaseLuxThreshold) {
                    break;
                }
            }
        } else if (mSmoothedLux < mSmoothedLuxAtLastAdjustment) {
            if (mHysteresisState != HysteresisState.IMMEDIATE
                    && mHysteresisState != HysteresisState.DECREASING) {
                if (DEBUG) {
                    Slog.d(TAG, "ALS transitioned to brightness decreasing state");
                }
                mHysteresisState = HysteresisState.DECREASING;
                mHysteresisCount = 0;
            }
            for (; newStepIndex >= 0; newStepIndex--) {
                if (mSmoothedLux > mBrightnessSteps[newStepIndex].mDecreaseLuxThreshold) {
                    break;
                }
            }
        }

        if (mHysteresisState == HysteresisState.IMMEDIATE) {
            mCurrentBrightnessStepIndex = newStepIndex;
            mSmoothedLuxAtLastAdjustment = mSmoothedLux;
            mHysteresisState = HysteresisState.STABLE;
            mHysteresisCount = 0;
            sendBrightnessAdjustment(mBrightnessSteps[newStepIndex].mBrightnessValue);
            return;
        }

        if (newStepIndex == mCurrentBrightnessStepIndex) {
            return;
        }

        mHysteresisCount++;
        if (DEBUG) {
            Slog.d(TAG, "Incremented hysteresis count to " + mHysteresisCount + " (lux went from "
                    + mSmoothedLuxAtLastAdjustment + " to " + mSmoothedLux + ")");
        }
        if (mHysteresisCount >= HYSTERESIS_THRESHOLD) {
            mCurrentBrightnessStepIndex = newStepIndex;
            mSmoothedLuxAtLastAdjustment = mSmoothedLux;
            mHysteresisCount = 1;
            sendBrightnessAdjustment(mBrightnessSteps[newStepIndex].mBrightnessValue);
        }
    }

    @MainThread
    private void handleDisplayChange() {
        DisplayManagerInternal displayManagerInternal = LocalServices.getService(
                DisplayManagerInternal.class);
        DisplayInfo displayInfo = displayManagerInternal.getDisplayInfo(Display.DEFAULT_DISPLAY);
        if (displayInfo == null) {
            return;
        }
        synchronized (sAmbientControllerLock) {
            if (Objects.equals(mCurrentDefaultDisplayUniqueId, displayInfo.uniqueId)) {
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "Default display changed: resetting the light sensor");
            }
            // Keep track of current default display
            mCurrentDefaultDisplayUniqueId = displayInfo.uniqueId;
            // Clear all existing sensor listeners
            if (!mAmbientKeyboardBacklightListeners.isEmpty()) {
                removeSensorListener(mLightSensor);
            }
            mLightSensor = getAmbientLightSensor(
                    displayManagerInternal.getAmbientLightSensorData(Display.DEFAULT_DISPLAY));
            // Re-add sensor listeners if required;
            if (!mAmbientKeyboardBacklightListeners.isEmpty()) {
                addSensorListener(mLightSensor);
            }
        }
    }

    private Sensor getAmbientLightSensor(
            DisplayManagerInternal.AmbientLightSensorData ambientSensor) {
        SensorManager sensorManager = Objects.requireNonNull(
                mContext.getSystemService(SensorManager.class));
        if (DEBUG) {
            Slog.d(TAG, "Ambient Light sensor data: " + ambientSensor);
        }
        return SensorUtils.findSensor(sensorManager, ambientSensor.sensorType,
                ambientSensor.sensorName, Sensor.TYPE_LIGHT);
    }

    private void updateSmoothedLux(float rawLux) {
        // For the first sensor reading, use raw lux value directly without smoothing.
        if (mHysteresisState == HysteresisState.IMMEDIATE) {
            mSmoothedLux = (int) rawLux;
        } else {
            mSmoothedLux =
                    (int) (mSmoothingConstant * rawLux + (1 - mSmoothingConstant) * mSmoothedLux);
        }
        if (DEBUG) {
            Slog.d(TAG, "Current smoothed lux from ALS = " + mSmoothedLux);
        }
    }

    @VisibleForTesting
    public void addSensorListener(@Nullable Sensor sensor) {
        SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        if (sensorManager == null || sensor == null) {
            return;
        }
        // Reset values before registering listener
        reset();
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        if (DEBUG) {
            Slog.d(TAG, "Registering ALS listener");
        }
    }

    private void removeSensorListener(@Nullable Sensor sensor) {
        SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        if (sensorManager == null || sensor == null) {
            return;
        }
        sensorManager.unregisterListener(this, sensor);
        if (DEBUG) {
            Slog.d(TAG, "Unregistering ALS listener");
        }
    }

    private void initConfiguration() {
        Resources res = mContext.getResources();
        int[] brightnessValueArray = res.getIntArray(
                com.android.internal.R.array.config_autoKeyboardBacklightBrightnessValues);
        int[] decreaseThresholdArray = res.getIntArray(
                com.android.internal.R.array.config_autoKeyboardBacklightDecreaseLuxThreshold);
        int[] increaseThresholdArray = res.getIntArray(
                com.android.internal.R.array.config_autoKeyboardBacklightIncreaseLuxThreshold);
        if (brightnessValueArray.length != decreaseThresholdArray.length
                || decreaseThresholdArray.length != increaseThresholdArray.length) {
            throw new IllegalArgumentException(
                    "The config files for auto keyboard backlight brightness must contain arrays "
                            + "of equal lengths");
        }
        final int size = brightnessValueArray.length;
        mBrightnessSteps = new BrightnessStep[size];
        for (int i = 0; i < size; i++) {
            int increaseThreshold =
                    increaseThresholdArray[i] < 0 ? Integer.MAX_VALUE : increaseThresholdArray[i];
            int decreaseThreshold =
                    decreaseThresholdArray[i] < 0 ? Integer.MIN_VALUE : decreaseThresholdArray[i];
            mBrightnessSteps[i] = new BrightnessStep(brightnessValueArray[i], increaseThreshold,
                    decreaseThreshold);
        }

        int numSteps = mBrightnessSteps.length;
        if (numSteps == 0 || mBrightnessSteps[0].mDecreaseLuxThreshold != Integer.MIN_VALUE
                || mBrightnessSteps[numSteps - 1].mIncreaseLuxThreshold != Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "The config files for auto keyboard backlight brightness must contain arrays "
                            + "of length > 0 and have -1 or Integer.MIN_VALUE as lower bound for "
                            + "decrease thresholds and -1 or Integer.MAX_VALUE as upper bound for "
                            + "increase thresholds");
        }

        final TypedValue smoothingConstantValue = new TypedValue();
        res.getValue(
                com.android.internal.R.dimen.config_autoKeyboardBrightnessSmoothingConstant,
                smoothingConstantValue,
                true /*resolveRefs*/);
        mSmoothingConstant = smoothingConstantValue.getFloat();
        if (mSmoothingConstant <= 0.0 || mSmoothingConstant > 1.0) {
            throw new IllegalArgumentException(
                    "The config files for auto keyboard backlight brightness must contain "
                            + "smoothing constant in range (0.0, 1.0].");
        }

        if (DEBUG) {
            Log.d(TAG, "Brightness steps: " + Arrays.toString(mBrightnessSteps)
                    + " Smoothing constant = " + mSmoothingConstant);
        }
    }

    private void reset() {
        mHysteresisState = HysteresisState.IMMEDIATE;
        mSmoothedLux = 0;
        mSmoothedLuxAtLastAdjustment = 0;
        mCurrentBrightnessStepIndex = -1;
    }

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_BRIGHTNESS_CALLBACK:
                handleBrightnessCallback((int) msg.obj);
                return true;
            case MSG_SETUP_DISPLAY_AND_SENSOR:
                handleDisplayChange();
                return true;
        }
        return false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        handleAmbientLuxChange(event.values[0]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDisplayAdded(int displayId) {
        handleDisplayChange();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        handleDisplayChange();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        handleDisplayChange();
    }

    public interface AmbientKeyboardBacklightListener {
        /**
         * @param value between [0, 255] to which keyboard backlight needs to be set according
         *              to Ambient light sensor.
         */
        void onKeyboardBacklightValueChanged(int value);
    }

    private static class BrightnessStep {
        private final int mBrightnessValue;
        private final int mIncreaseLuxThreshold;
        private final int mDecreaseLuxThreshold;

        private BrightnessStep(int brightnessValue, int increaseLuxThreshold,
                int decreaseLuxThreshold) {
            mBrightnessValue = brightnessValue;
            mIncreaseLuxThreshold = increaseLuxThreshold;
            mDecreaseLuxThreshold = decreaseLuxThreshold;
        }

        @Override
        public String toString() {
            return "BrightnessStep{" + "mBrightnessValue=" + mBrightnessValue
                    + ", mIncreaseThreshold=" + mIncreaseLuxThreshold + ", mDecreaseThreshold="
                    + mDecreaseLuxThreshold + '}';
        }
    }
}
