/**
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

package android.view;

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

/**
 * {@link ScrollFeedbackProvider} that performs haptic feedback when scrolling.
 *
 * <p>Each scrolling widget should have its own instance of this class to ensure that scroll state
 * is isolated.
 *
 * <p>Check {@link ScrollFeedbackProvider} for details on the arguments that should be passed to the
 * methods in this class. To check if your input device ID, source, and motion axis are valid for
 * haptic feedback, you can use the
 * {@link ViewConfiguration#isHapticScrollFeedbackEnabled(int, int, int)} API.
 *
 * @hide
 */
public class HapticScrollFeedbackProvider implements ScrollFeedbackProvider {
    private static final String TAG = "HapticScrollFeedbackProvider";

    private static final int TICK_INTERVAL_NO_TICK = 0;
    private static final boolean INITIAL_END_OF_LIST_HAPTICS_ENABLED = false;

    private final View mView;
    private final ViewConfiguration mViewConfig;
    /**
     * Flag to disable the logic in this class if the View-based scroll haptics implementation is
     * enabled. If {@code false}, this class will continue to run despite the View's scroll
     * haptics implementation being enabled. This value should be set to {@code true} when this
     * class is directly used by the View class.
     */
    private final boolean mDisabledIfViewPlaysScrollHaptics;


    // Info about the cause of the latest scroll event.
    /** The ID of the {link @InputDevice} that caused the latest scroll event. */
    private int mDeviceId = -1;
    /** The axis on which the latest scroll event happened. */
    private int mAxis = -1;
    /** The {@link InputDevice} source from which the latest scroll event happened. */
    private int mSource = -1;

    /** The tick interval corresponding to the current InputDevice/source/axis. */
    private int mTickIntervalPixels = TICK_INTERVAL_NO_TICK;
    private int mTotalScrollPixels = 0;
    private boolean mCanPlayLimitFeedback = INITIAL_END_OF_LIST_HAPTICS_ENABLED;
    private boolean mHapticScrollFeedbackEnabled = false;

    public HapticScrollFeedbackProvider(@NonNull View view) {
        this(view, ViewConfiguration.get(view.getContext()),
                /* disabledIfViewPlaysScrollHaptics= */ true);
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public HapticScrollFeedbackProvider(
            View view, ViewConfiguration viewConfig, boolean disabledIfViewPlaysScrollHaptics) {
        mView = view;
        mViewConfig = viewConfig;
        mDisabledIfViewPlaysScrollHaptics = disabledIfViewPlaysScrollHaptics;
    }

    @Override
    public void onScrollProgress(int inputDeviceId, int source, int axis, int deltaInPixels) {
        maybeUpdateCurrentConfig(inputDeviceId, source, axis);
        if (!mHapticScrollFeedbackEnabled) {
            return;
        }

        // Unlock limit feedback regardless of scroll tick being enabled as long as there's a
        // non-zero scroll progress.
        if (deltaInPixels != 0) {
            mCanPlayLimitFeedback = true;
        }

        if (mTickIntervalPixels == TICK_INTERVAL_NO_TICK) {
            // There's no valid tick interval. Exit early before doing any further computation.
            return;
        }

        mTotalScrollPixels += deltaInPixels;

        if (Math.abs(mTotalScrollPixels) >= mTickIntervalPixels) {
            mTotalScrollPixels %= mTickIntervalPixels;
            if (android.os.vibrator.Flags.hapticFeedbackInputSourceCustomizationEnabled()) {
                mView.performHapticFeedbackForInputDevice(
                        HapticFeedbackConstants.SCROLL_TICK, inputDeviceId, source, /* flags= */ 0);
            } else {
                mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_TICK);
            }
        }
    }

    @Override
    public void onScrollLimit(int inputDeviceId, int source, int axis, boolean isStart) {
        maybeUpdateCurrentConfig(inputDeviceId, source, axis);
        if (!mHapticScrollFeedbackEnabled) {
            return;
        }

        if (!mCanPlayLimitFeedback) {
            return;
        }
        if (android.os.vibrator.Flags.hapticFeedbackInputSourceCustomizationEnabled()) {
            mView.performHapticFeedbackForInputDevice(
                    HapticFeedbackConstants.SCROLL_LIMIT, inputDeviceId, source, /* flags= */ 0);
        } else {
            mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_LIMIT);
        }

        mCanPlayLimitFeedback = false;
    }

    @Override
    public void onSnapToItem(int inputDeviceId, int source, int axis) {
        maybeUpdateCurrentConfig(inputDeviceId, source, axis);
        if (!mHapticScrollFeedbackEnabled) {
            return;
        }
        if (android.os.vibrator.Flags.hapticFeedbackInputSourceCustomizationEnabled()) {
            mView.performHapticFeedbackForInputDevice(
                    HapticFeedbackConstants.SCROLL_ITEM_FOCUS, inputDeviceId, source,
                    /* flags= */ 0);
        } else {
            mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_ITEM_FOCUS);
        }
        mCanPlayLimitFeedback = true;
    }

    private void maybeUpdateCurrentConfig(int deviceId, int source, int axis) {
        if (mAxis != axis || mSource != source || mDeviceId != deviceId) {
            mSource = source;
            mAxis = axis;
            mDeviceId = deviceId;

            if (mDisabledIfViewPlaysScrollHaptics
                    && (source == InputDevice.SOURCE_ROTARY_ENCODER)
                    && mViewConfig.isViewBasedRotaryEncoderHapticScrollFeedbackEnabled()) {
                mHapticScrollFeedbackEnabled = false;
                return;
            }

            mHapticScrollFeedbackEnabled =
                    mViewConfig.isHapticScrollFeedbackEnabled(deviceId, axis, source);
            mCanPlayLimitFeedback = INITIAL_END_OF_LIST_HAPTICS_ENABLED;
            mTotalScrollPixels = 0;
            updateTickIntervals(deviceId, source, axis);
        }
    }

    private void updateTickIntervals(int deviceId, int source, int axis) {
        mTickIntervalPixels = mHapticScrollFeedbackEnabled
                ? mViewConfig.getHapticScrollFeedbackTickInterval(deviceId, axis, source)
                : TICK_INTERVAL_NO_TICK;
    }
}
