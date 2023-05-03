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

import static com.android.internal.R.dimen.config_rotaryEncoderAxisScrollTickInterval;

import com.android.internal.annotations.VisibleForTesting;

/**
 * {@link ScrollFeedbackProvider} that performs haptic feedback when scrolling.
 *
 * <p>Each scrolling widget should have its own instance of this class to ensure that scroll state
 * is isolated.
 *
 * @hide
 */
public class HapticScrollFeedbackProvider implements ScrollFeedbackProvider {
    private static final String TAG = "HapticScrollFeedbackProvider";

    private static final int TICK_INTERVAL_NO_TICK = 0;
    private static final int TICK_INTERVAL_UNSET = Integer.MAX_VALUE;

    private final View mView;


    // Info about the cause of the latest scroll event.
    /** The ID of the {link @InputDevice} that caused the latest scroll event. */
    private int mDeviceId = -1;
    /** The axis on which the latest scroll event happened. */
    private int mAxis = -1;
    /** The {@link InputDevice} source from which the latest scroll event happened. */
    private int mSource = -1;

    /**
     * Cache for tick interval for scroll tick caused by a {@link InputDevice#SOURCE_ROTARY_ENCODER}
     * on {@link MotionEvent#AXIS_SCROLL}. Set to -1 if the value has not been fetched and cached.
     */
    private int mRotaryEncoderAxisScrollTickIntervalPixels = TICK_INTERVAL_UNSET;
    /** The tick interval corresponding to the current InputDevice/source/axis. */
    private int mTickIntervalPixels = TICK_INTERVAL_NO_TICK;
    private int mTotalScrollPixels = 0;
    private boolean mCanPlayLimitFeedback = true;

    public HapticScrollFeedbackProvider(View view) {
        this(view, /* rotaryEncoderAxisScrollTickIntervalPixels= */ TICK_INTERVAL_UNSET);
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public HapticScrollFeedbackProvider(View view, int rotaryEncoderAxisScrollTickIntervalPixels) {
        mView = view;
        mRotaryEncoderAxisScrollTickIntervalPixels = rotaryEncoderAxisScrollTickIntervalPixels;
    }

    @Override
    public void onScrollProgress(MotionEvent event, int axis, int deltaInPixels) {
        maybeUpdateCurrentConfig(event, axis);

        if (mTickIntervalPixels == TICK_INTERVAL_NO_TICK) {
            // There's no valid tick interval. Exit early before doing any further computation.
            return;
        }

        mTotalScrollPixels += deltaInPixels;

        if (Math.abs(mTotalScrollPixels) >= mTickIntervalPixels) {
            mTotalScrollPixels %= mTickIntervalPixels;
            // TODO(b/239594271): create a new `performHapticFeedbackForDevice` and use that here.
            mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_TICK);
        }

        mCanPlayLimitFeedback = true;
    }

    @Override
    public void onScrollLimit(MotionEvent event, int axis, boolean isStart) {
        maybeUpdateCurrentConfig(event, axis);

        if (!mCanPlayLimitFeedback) {
            return;
        }

        // TODO(b/239594271): create a new `performHapticFeedbackForDevice` and use that here.
        mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_LIMIT);

        mCanPlayLimitFeedback = false;
    }

    @Override
    public void onSnapToItem(MotionEvent event, int axis) {
        // TODO(b/239594271): create a new `performHapticFeedbackForDevice` and use that here.
        mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_ITEM_FOCUS);
        mCanPlayLimitFeedback = true;
    }

    private void maybeUpdateCurrentConfig(MotionEvent event, int axis) {
        int source = event.getSource();
        int deviceId = event.getDeviceId();

        if (mAxis != axis || mSource != source || mDeviceId != deviceId) {
            mSource = source;
            mAxis = axis;
            mDeviceId = deviceId;

            mCanPlayLimitFeedback = true;
            mTotalScrollPixels = 0;
            calculateTickIntervals(source, axis);
        }
    }

    private void calculateTickIntervals(int source, int axis) {
        mTickIntervalPixels = TICK_INTERVAL_NO_TICK;

        if (axis == MotionEvent.AXIS_SCROLL && source == InputDevice.SOURCE_ROTARY_ENCODER) {
            if (mRotaryEncoderAxisScrollTickIntervalPixels == TICK_INTERVAL_UNSET) {
                // Value has not been fetched  yet. Fetch and cache it.
                mRotaryEncoderAxisScrollTickIntervalPixels =
                        mView.getContext().getResources().getDimensionPixelSize(
                                config_rotaryEncoderAxisScrollTickInterval);
                if (mRotaryEncoderAxisScrollTickIntervalPixels < 0) {
                    mRotaryEncoderAxisScrollTickIntervalPixels = TICK_INTERVAL_NO_TICK;
                }
            }
            mTickIntervalPixels = mRotaryEncoderAxisScrollTickIntervalPixels;
        }
    }
}
