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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.view.flags.Flags;

import com.android.internal.annotations.VisibleForTesting;

/**
 * {@link ScrollFeedbackProvider} that performs haptic feedback when scrolling.
 *
 * <p>Each scrolling widget should have its own instance of this class to ensure that scroll state
 * is isolated.
 */
@FlaggedApi(Flags.FLAG_SCROLL_FEEDBACK_API)
public class HapticScrollFeedbackProvider implements ScrollFeedbackProvider {
    private static final String TAG = "HapticScrollFeedbackProvider";

    private static final int TICK_INTERVAL_NO_TICK = 0;

    private final View mView;
    private final ViewConfiguration mViewConfig;


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
    /** The tick interval corresponding to the current InputDevice/source/axis. */
    private int mTickIntervalPixels = TICK_INTERVAL_NO_TICK;
    private int mTotalScrollPixels = 0;
    private boolean mCanPlayLimitFeedback = true;
    private boolean mHapticScrollFeedbackEnabled = false;

    public HapticScrollFeedbackProvider(@NonNull View view) {
        this(view, ViewConfiguration.get(view.getContext()));
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public HapticScrollFeedbackProvider(View view, ViewConfiguration viewConfig) {
        mView = view;
        mViewConfig = viewConfig;
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
            // TODO(b/239594271): create a new `performHapticFeedbackForDevice` and use that here.
            mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_TICK);
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

        // TODO(b/239594271): create a new `performHapticFeedbackForDevice` and use that here.
        mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_LIMIT);

        mCanPlayLimitFeedback = false;
    }

    @Override
    public void onSnapToItem(int inputDeviceId, int source, int axis) {
        maybeUpdateCurrentConfig(inputDeviceId, source, axis);
        if (!mHapticScrollFeedbackEnabled) {
            return;
        }
        // TODO(b/239594271): create a new `performHapticFeedbackForDevice` and use that here.
        mView.performHapticFeedback(HapticFeedbackConstants.SCROLL_ITEM_FOCUS);
        mCanPlayLimitFeedback = true;
    }

    private void maybeUpdateCurrentConfig(int deviceId, int source, int axis) {
        if (mAxis != axis || mSource != source || mDeviceId != deviceId) {
            mSource = source;
            mAxis = axis;
            mDeviceId = deviceId;

            mHapticScrollFeedbackEnabled =
                    mViewConfig.isHapticScrollFeedbackEnabled(deviceId, axis, source);
            mCanPlayLimitFeedback = true;
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
