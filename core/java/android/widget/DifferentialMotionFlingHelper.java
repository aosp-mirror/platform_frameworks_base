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

package android.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.flags.FeatureFlags;
import android.widget.flags.FeatureFlagsImpl;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Helper for controlling differential motion flings.
 *
 * <p><b>Differential motion</b> here refers to motions that report change in position instead of
 * absolution position. For instance, differential data points of 2, -1, 5 represent: there was
 * a movement by "2" units, then by "-1" units, then by "5" units. Examples of motions reported
 * differentially include motions from {@link MotionEvent#AXIS_SCROLL}.
 *
 * <p>The client should call {@link #onMotionEvent} when a differential motion event happens on
 * the target View (that is, the View on which we want to fling), and this class processes the event
 * to orchestrate fling.
 *
 * <p>Note that this helper class currently works to control fling only in one direction at a time.
 * As such, it works independently of horizontal/vertical orientations. It requests its client to
 * start/stop fling, and it's up to the client to choose the fling direction based on its specific
 * internal configurations and/or preferences.
 *
 * @hide
 */
public class DifferentialMotionFlingHelper {
    private final Context mContext;
    private final DifferentialMotionFlingTarget mTarget;

    private final FlingVelocityThresholdCalculator mVelocityThresholdCalculator;
    private final DifferentialVelocityProvider mVelocityProvider;

    private final FeatureFlags mWidgetFeatureFlags;

    @Nullable private VelocityTracker mVelocityTracker;

    private float mLastFlingVelocity;

    private int mLastProcessedAxis = -1;
    private int mLastProcessedSource = -1;
    private int mLastProcessedDeviceId = -1;

    // Initialize min and max to +infinity and 0, to effectively disable fling at start.
    private final int[] mFlingVelocityThresholds = new int[] {Integer.MAX_VALUE, 0};

    /** Interface to calculate the fling velocity thresholds. Helps fake during testing. */
    @VisibleForTesting
    public interface FlingVelocityThresholdCalculator {
        /**
         * Calculates the fling velocity thresholds (in pixels/second) and puts them in a provided
         * store.
         *
         * @param context the context associated with the View that may be flung.
         * @param store an at-least size-2 int array. The method will overwrite positions 0 and 1
         *             with the min and max fling velocities, respectively.
         * @param event the event that may trigger fling.
         * @param axis the axis being processed for the event.
         */
        void calculateFlingVelocityThresholds(
                Context context, int[] store, MotionEvent event, int axis);
    }

    /**
     * Interface to provide velocity. Helps fake during testing.
     *
     * <p>The client should call {@link #getCurrentVelocity(VelocityTracker, MotionEvent, int)} each
     * time it wants to consider a {@link MotionEvent} towards the latest velocity, and the
     * interface handles providing velocity that accounts for the latest and all past events.
     */
    @VisibleForTesting
    public interface DifferentialVelocityProvider {
        /**
         * Returns the latest velocity.
         *
         * @param vt the {@link VelocityTracker} to be used to compute velocity.
         * @param event the latest event to be considered in the velocity computations.
         * @param axis the axis being processed for the event.
         * @return the calculated, latest velocity.
         */
        float getCurrentVelocity(VelocityTracker vt, MotionEvent event, int axis);
    }

    /**
     * Represents an entity that may be flung by a differential motion or an entity that initiates
     * fling on a target View.
     */
    public interface DifferentialMotionFlingTarget {
        /**
         * Start flinging on the target View by a given velocity.
         *
         * @param velocity the fling velocity, in pixels/second.
         * @return {@code true} if fling was successfully initiated, {@code false} otherwise.
         */
        boolean startDifferentialMotionFling(float velocity);

        /** Stop any ongoing fling on the target View that is caused by a differential motion. */
        void stopDifferentialMotionFling();

        /**
         * Returns the scaled scroll factor to be used for differential motions. This is the
         * value that the raw {@link MotionEvent} values should be multiplied with to get pixels.
         *
         * <p>This usually is one of the values provided by {@link ViewConfiguration}. It is
         * up to the client to choose and provide any value as per its internal configuration.
         *
         * @see ViewConfiguration#getScaledHorizontalScrollFactor()
         * @see ViewConfiguration#getScaledVerticalScrollFactor()
         */
        float getScaledScrollFactor();
    }

    /** Constructs an instance for a given {@link DifferentialMotionFlingTarget}. */
    public DifferentialMotionFlingHelper(
            Context context,
            DifferentialMotionFlingTarget target) {
        this(context,
                target,
                DifferentialMotionFlingHelper::calculateFlingVelocityThresholds,
                DifferentialMotionFlingHelper::getCurrentVelocity,
                /* widgetFeatureFlags= */ new FeatureFlagsImpl());
    }

    @VisibleForTesting
    public DifferentialMotionFlingHelper(
            Context context,
            DifferentialMotionFlingTarget target,
            FlingVelocityThresholdCalculator velocityThresholdCalculator,
            DifferentialVelocityProvider velocityProvider,
            FeatureFlags widgetFeatureFlags) {
        mContext = context;
        mTarget = target;
        mVelocityThresholdCalculator = velocityThresholdCalculator;
        mVelocityProvider = velocityProvider;
        mWidgetFeatureFlags = widgetFeatureFlags;
    }

    /**
     * Called to report when a differential motion happens on the View that's the target for fling.
     *
     * @param event the {@link MotionEvent} being reported.
     * @param axis the axis being processed by the target View.
     */
    public void onMotionEvent(MotionEvent event, int axis) {
        if (!mWidgetFeatureFlags.enablePlatformWidgetDifferentialMotionFling()) {
            return;
        }
        boolean flingParamsChanged = calculateFlingVelocityThresholds(event, axis);
        if (mFlingVelocityThresholds[0] == Integer.MAX_VALUE) {
            // Integer.MAX_VALUE means that the device does not support fling for the current
            // configuration. Do not proceed any further.
            recycleVelocityTracker();
            return;
        }

        float scaledVelocity =
                getCurrentVelocity(event, axis) * mTarget.getScaledScrollFactor();

        float velocityDirection = Math.signum(scaledVelocity);
        // Stop ongoing fling if there has been state changes affecting fling, or if the current
        // velocity (if non-zero) is opposite of the velocity that last caused fling.
        if (flingParamsChanged
                || (velocityDirection != Math.signum(mLastFlingVelocity)
                    && velocityDirection != 0)) {
            mTarget.stopDifferentialMotionFling();
        }

        if (Math.abs(scaledVelocity) < mFlingVelocityThresholds[0]) {
            return;
        }

        // Clamp the scaled velocity between [-max, max].
        // e.g. if max=100, and vel=200
        // vel = max(-100, min(200, 100)) = max(-100, 100) = 100
        // e.g. if max=100, and vel=-200
        // vel = max(-100, min(-200, 100)) = max(-100, -200) = -100
        scaledVelocity =
                Math.max(
                        -mFlingVelocityThresholds[1],
                        Math.min(scaledVelocity, mFlingVelocityThresholds[1]));

        boolean flung = mTarget.startDifferentialMotionFling(scaledVelocity);
        mLastFlingVelocity = flung ? scaledVelocity : 0;
    }

    /**
     * Calculates fling velocity thresholds based on the provided event and axis, and returns {@code
     * true} if there has been a change of any params that may affect fling velocity thresholds.
     */
    private boolean calculateFlingVelocityThresholds(MotionEvent event, int axis) {
        int source = event.getSource();
        int deviceId = event.getDeviceId();
        if (mLastProcessedSource != source
                || mLastProcessedDeviceId != deviceId
                || mLastProcessedAxis != axis) {
            mVelocityThresholdCalculator.calculateFlingVelocityThresholds(
                    mContext, mFlingVelocityThresholds, event, axis);
            // Save data about this processing so that we don't have to re-process fling thresholds
            // for similar parameters.
            mLastProcessedSource = source;
            mLastProcessedDeviceId = deviceId;
            mLastProcessedAxis = axis;
            return true;
        }
        return false;
    }

    private static void calculateFlingVelocityThresholds(
            Context context, int[] buffer, MotionEvent event, int axis) {
        int source = event.getSource();
        int deviceId = event.getDeviceId();

        ViewConfiguration vc = ViewConfiguration.get(context);
        buffer[0] = vc.getScaledMinimumFlingVelocity(deviceId, axis, source);
        buffer[1] = vc.getScaledMaximumFlingVelocity(deviceId, axis, source);
    }

    private float getCurrentVelocity(MotionEvent event, int axis) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }

        return mVelocityProvider.getCurrentVelocity(mVelocityTracker, event, axis);
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private static float getCurrentVelocity(VelocityTracker vt, MotionEvent event, int axis) {
        vt.addMovement(event);
        vt.computeCurrentVelocity(1000);
        return vt.getAxisVelocity(axis);
    }
}
