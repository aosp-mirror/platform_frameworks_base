/*
 * Copyright (C) 2016 The Android Open Source Project
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


package com.android.documentsui.dirlist;

import android.graphics.Point;
import android.support.annotation.VisibleForTesting;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Provides auto-scrolling upon request when user's interaction with the application
 * introduces a natural intent to scroll. Used by {@link BandController} and
 * {@link DragScrollListener} to allow auto scrolling when user either does band selection, or
 * attempting to drag and drop files to somewhere off the current screen.
 */
public final class ViewAutoScroller implements Runnable {
    public static final int NOT_SET = -1;
    /**
     * The number of milliseconds of scrolling at which scroll speed continues to increase.
     * At first, the scroll starts slowly; then, the rate of scrolling increases until it
     * reaches its maximum value at after this many milliseconds.
     */
    private static final long SCROLL_ACCELERATION_LIMIT_TIME_MS = 2000;

    // Top and bottom inner buffer such that user's cursor does not have to be exactly off screen
    // for auto scrolling to begin
    private final int mTopBottomThreshold;
    private final ScrollDistanceDelegate mCalcDelegate;
    private final ScrollActionDelegate mUiDelegate;
    private final LongSupplier mCurrentTime;

    private long mScrollStartTime = NOT_SET;

    public ViewAutoScroller(
            int topBottomThreshold,
            ScrollDistanceDelegate calcDelegate,
            ScrollActionDelegate uiDelegate) {
        this(topBottomThreshold, calcDelegate, uiDelegate, System::currentTimeMillis);
    }

    @VisibleForTesting
    ViewAutoScroller(
            int topBottomThreshold,
            ScrollDistanceDelegate calcDelegate,
            ScrollActionDelegate uiDelegate,
            LongSupplier clock) {
        mTopBottomThreshold = topBottomThreshold;
        mCalcDelegate = calcDelegate;
        mUiDelegate = uiDelegate;
        mCurrentTime = clock;
    }

    /**
     * Attempts to smooth-scroll the view at the given UI frame. Application should be
     * responsible to do any clean up (such as unsubscribing scrollListeners) after the run has
     * finished, and re-run this method on the next UI frame if applicable.
     */
    @Override
    public void run() {
        // Compute the number of pixels the pointer's y-coordinate is past the view.
        // Negative values mean the pointer is at or before the top of the view, and
        // positive values mean that the pointer is at or after the bottom of the view. Note
        // that top/bottom threshold is added here so that the view still scrolls when the
        // pointer are in these buffer pixels.
        int pixelsPastView = 0;

        if (mCalcDelegate.getCurrentPosition().y <= mTopBottomThreshold) {
            pixelsPastView = mCalcDelegate.getCurrentPosition().y - mTopBottomThreshold;
        } else if (mCalcDelegate.getCurrentPosition().y >= mCalcDelegate.getViewHeight()
                - mTopBottomThreshold) {
            pixelsPastView = mCalcDelegate.getCurrentPosition().y - mCalcDelegate.getViewHeight()
                    + mTopBottomThreshold;
        }

        if (!mCalcDelegate.isActive() || pixelsPastView == 0) {
            // If the operation that started the scrolling is no longer inactive, or if it is active
            // but not at the edge of the view, no scrolling is necessary.
            mScrollStartTime = NOT_SET;
            return;
        }

        if (mScrollStartTime == NOT_SET) {
            // If the pointer was previously not at the edge of the view but now is, set the
            // start time for the scroll.
            mScrollStartTime = mCurrentTime.getAsLong();
        }

        // Compute the number of pixels to scroll, and scroll that many pixels.
        final int numPixels = computeScrollDistance(
                pixelsPastView, mCurrentTime.getAsLong() - mScrollStartTime);
        mUiDelegate.scrollBy(numPixels);

        // Remove callback to this, and then properly run at next frame again
        mUiDelegate.removeCallback(this);
        mUiDelegate.runAtNextFrame(this);
    }

    /**
     * Computes the number of pixels to scroll based on how far the pointer is past the end
     * of the view and how long it has been there. Roughly based on ItemTouchHelper's
     * algorithm for computing the number of pixels to scroll when an item is dragged to the
     * end of a view.
     * @param pixelsPastView
     * @param scrollDuration
     * @return
     */
    public int computeScrollDistance(int pixelsPastView, long scrollDuration) {
        final int maxScrollStep = mCalcDelegate.getViewHeight();
        final int direction = (int) Math.signum(pixelsPastView);
        final int absPastView = Math.abs(pixelsPastView);

        // Calculate the ratio of how far out of the view the pointer currently resides to
        // the entire height of the view.
        final float outOfBoundsRatio = Math.min(
                1.0f, (float) absPastView / mCalcDelegate.getViewHeight());
        // Interpolate this ratio and use it to compute the maximum scroll that should be
        // possible for this step.
        final float cappedScrollStep =
                direction * maxScrollStep * smoothOutOfBoundsRatio(outOfBoundsRatio);

        // Likewise, calculate the ratio of the time spent in the scroll to the limit.
        final float timeRatio = Math.min(
                1.0f, (float) scrollDuration / SCROLL_ACCELERATION_LIMIT_TIME_MS);
        // Interpolate this ratio and use it to compute the final number of pixels to
        // scroll.
        final int numPixels = (int) (cappedScrollStep * smoothTimeRatio(timeRatio));

        // If the final number of pixels to scroll ends up being 0, the view should still
        // scroll at least one pixel.
        return numPixels != 0 ? numPixels : direction;
    }

    /**
     * Interpolates the given out of bounds ratio on a curve which starts at (0,0) and ends
     * at (1,1) and quickly approaches 1 near the start of that interval. This ensures that
     * drags that are at the edge or barely past the edge of the view still cause sufficient
     * scrolling. The equation y=(x-1)^5+1 is used, but this could also be tweaked if
     * needed.
     * @param ratio A ratio which is in the range [0, 1].
     * @return A "smoothed" value, also in the range [0, 1].
     */
    private float smoothOutOfBoundsRatio(float ratio) {
        return (float) Math.pow(ratio - 1.0f, 5) + 1.0f;
    }

    /**
     * Interpolates the given time ratio on a curve which starts at (0,0) and ends at (1,1)
     * and stays close to 0 for most input values except those very close to 1. This ensures
     * that scrolls start out very slowly but speed up drastically after the scroll has been
     * in progress close to SCROLL_ACCELERATION_LIMIT_TIME_MS. The equation y=x^5 is used,
     * but this could also be tweaked if needed.
     * @param ratio A ratio which is in the range [0, 1].
     * @return A "smoothed" value, also in the range [0, 1].
     */
    private float smoothTimeRatio(float ratio) {
        return (float) Math.pow(ratio, 5);
    }

    /**
     * Used by {@link run} to properly calculate the proper amount of pixels to scroll given time
     * passed since scroll started, and to properly scroll / proper listener clean up if necessary.
     */
    interface ScrollDistanceDelegate {
        public Point getCurrentPosition();
        public int getViewHeight();
        public boolean isActive();
    }

    /**
     * Used by {@link run} to do UI tasks, such as scrolling and rerunning at next UI cycle.
     */
    interface ScrollActionDelegate {
        public void scrollBy(int dy);
        public void runAtNextFrame(Runnable r);
        public void removeCallback(Runnable r);
    }
}