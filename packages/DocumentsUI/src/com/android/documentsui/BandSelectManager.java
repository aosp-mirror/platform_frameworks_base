/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.Events.isMouseEvent;
import static com.android.internal.util.Preconditions.checkState;

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * Provides mouse driven band-select support when used in conjuction with {@link RecyclerView} and
 * {@link MultiSelectManager}. This class is responsible for rendering the band select overlay and
 * selecting overlaid items via MultiSelectManager.
 */
public class BandSelectManager extends RecyclerView.SimpleOnItemTouchListener {

    private static final int NOT_SELECTED = -1;
    private static final int NOT_SET = -1;

    // For debugging purposes.
    private static final String TAG = "BandSelectManager";
    private static final boolean DEBUG = false;

    private final RecyclerView mRecyclerView;
    private final MultiSelectManager mSelectManager;
    private final Drawable mRegionSelectorDrawable;
    private final SparseBooleanArray mSelectedByBand = new SparseBooleanArray();

    private boolean mIsBandSelectActive = false;
    private Point mOrigin;
    private Point mPointer;
    private Rect mBounds;

    // Maintain the last selection made by band, so if bounds shrink back, we can deselect
    // the respective items.
    private int mCursorDeltaY = 0;
    private int mFirstSelected = NOT_SELECTED;

    // The time at which the current band selection-induced scroll began. If no scroll is in
    // progress, the value is NOT_SET.
    private long mScrollStartTime = NOT_SET;
    private final Runnable mScrollRunnable = new Runnable() {
        /**
         * The number of milliseconds of scrolling at which scroll speed continues to increase. At
         * first, the scroll starts slowly; then, the rate of scrolling increases until it reaches
         * its maximum value at after this many milliseconds.
         */
        private static final long SCROLL_ACCELERATION_LIMIT_TIME_MS = 2000;

        @Override
        public void run() {
            // Compute the number of pixels the pointer's y-coordinate is past the view. Negative
            // values mean the pointer is at or before the top of the view, and positive values mean
            // that the pointer is at or after the bottom of the view. Note that one additional
            // pixel is added here so that the view still scrolls when the pointer is exactly at the
            // top or bottom.
            int pixelsPastView = 0;
            if (mPointer.y <= 0) {
                pixelsPastView = mPointer.y - 1;
            } else if (mPointer.y >= mRecyclerView.getHeight() - 1) {
                pixelsPastView = mPointer.y - mRecyclerView.getHeight() + 1;
            }

            if (!mIsBandSelectActive || pixelsPastView == 0) {
                // If band selection is inactive, or if it is active but not at the edge of the
                // view, no scrolling is necessary.
                mScrollStartTime = NOT_SET;
                return;
            }

            if (mScrollStartTime == NOT_SET) {
                // If the pointer was previously not at the edge of the view but now is, set the
                // start time for the scroll.
                mScrollStartTime = System.currentTimeMillis();
            }

            // Compute the number of pixels to scroll, and scroll that many pixels.
            final int numPixels = computeNumPixelsToScroll(
                    pixelsPastView, System.currentTimeMillis() - mScrollStartTime);
            mRecyclerView.scrollBy(0, numPixels);

            // Adjust the y-coordinate of the origin the opposite number of pixels so that the
            // origin remains in the same place relative to the view's items.
            mOrigin.y -= numPixels;
            resizeBandSelectRectangle();

            mRecyclerView.removeCallbacks(mScrollRunnable);
            mRecyclerView.postOnAnimation(this);
        }

        /**
         * Computes the number of pixels to scroll based on how far the pointer is past the end of
         * the view and how long it has been there. Roughly based on ItemTouchHelper's algorithm for
         * computing the number of pixels to scroll when an item is dragged to the end of a
         * {@link RecyclerView}.
         * @param pixelsPastView
         * @param scrollDuration
         * @return
         */
        private int computeNumPixelsToScroll(int pixelsPastView, long scrollDuration) {
            final int maxScrollStep = computeMaxScrollStep(mRecyclerView);
            final int direction = (int) Math.signum(pixelsPastView);
            final int absPastView = Math.abs(pixelsPastView);

            // Calculate the ratio of how far out of the view the pointer currently resides to the
            // entire height of the view.
            final float outOfBoundsRatio = Math.min(
                    1.0f, (float) absPastView / mRecyclerView.getHeight());
            // Interpolate this ratio and use it to compute the maximum scroll that should be
            // possible for this step.
            final float cappedScrollStep =
                    direction * maxScrollStep * smoothOutOfBoundsRatio(outOfBoundsRatio);

            // Likewise, calculate the ratio of the time spent in the scroll to the limit.
            final float timeRatio = Math.min(
                    1.0f, (float) scrollDuration / SCROLL_ACCELERATION_LIMIT_TIME_MS);
            // Interpolate this ratio and use it to compute the final number of pixels to scroll.
            final int numPixels = (int) (cappedScrollStep * smoothTimeRatio(timeRatio));

            // If the final number of pixels to scroll ends up being 0, the view should still scroll
            // at least one pixel.
            return numPixels != 0 ? numPixels : direction;
        }

        /**
         * Computes the maximum scroll allowed for a given animation frame. Currently, this
         * defaults to the height of the view, but this could be tweaked if this results in scrolls
         * that are too fast or too slow.
         * @param rv
         * @return
         */
        private int computeMaxScrollStep(RecyclerView rv) {
            return rv.getHeight();
        }

        /**
         * Interpolates the given out of bounds ratio on a curve which starts at (0,0) and ends at
         * (1,1) and quickly approaches 1 near the start of that interval. This ensures that drags
         * that are at the edge or barely past the edge of the view still cause sufficient
         * scrolling. The equation y=(x-1)^5+1 is used, but this could also be tweaked if needed.
         * @param ratio A ratio which is in the range [0, 1].
         * @return A "smoothed" value, also in the range [0, 1].
         */
        private float smoothOutOfBoundsRatio(float ratio) {
            return (float) Math.pow(ratio - 1.0f, 5) + 1.0f;
        }

        /**
         * Interpolates the given time ratio on a curve which starts at (0,0) and ends at (1,1) and
         * stays close to 0 for most input values except those very close to 1. This ensures that
         * scrolls start out very slowly but speed up drastically after the scroll has been in
         * progress close to SCROLL_ACCELERATION_LIMIT_TIME_MS. The equation y=x^5 is used, but this
         * could also be tweaked if needed.
         * @param ratio A ratio which is in the range [0, 1].
         * @return A "smoothed" value, also in the range [0, 1].
         */
        private float smoothTimeRatio(float ratio) {
            return (float) Math.pow(ratio, 5);
        }
    };

    /**
     * @param recyclerView
     * @param multiSelectManager
     */
    public BandSelectManager(RecyclerView recyclerView, MultiSelectManager multiSelectManager) {
        mRecyclerView = recyclerView;
        mSelectManager = multiSelectManager;
        mRegionSelectorDrawable =
            mRecyclerView.getContext().getTheme().getDrawable(R.drawable.band_select_overlay);

        mRecyclerView.addOnItemTouchListener(this);
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        // Only intercept the event if it was triggered by a mouse. If band select is inactive,
        // do not intercept ACTION_UP events as they will not be processed.
        return isMouseEvent(e) &&
                (mIsBandSelectActive || e.getActionMasked() != MotionEvent.ACTION_UP);
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        checkState(isMouseEvent(e));
        processMotionEvent(e);
    }

    /**
     * Processes a MotionEvent by starting, ending, or resizing the band select overlay.
     * @param e
     */
    private void processMotionEvent(MotionEvent e) {
        if (mIsBandSelectActive && e.getActionMasked() == MotionEvent.ACTION_UP) {
            endBandSelect();
            return;
        }

        mPointer = new Point((int) e.getX(), (int) e.getY());
        if (!mIsBandSelectActive) {
            startBandSelect();
        }

        scrollViewIfNecessary();
        resizeBandSelectRectangle();
        selectChildrenCoveredBySelection();
    }

    /**
     * Starts band select by adding the drawable to the RecyclerView's overlay.
     */
    private void startBandSelect() {
        if (DEBUG) Log.d(TAG, "Starting band select from (" + mPointer.x + "," + mPointer.y + ").");
        mIsBandSelectActive = true;
        mOrigin = mPointer;
        mRecyclerView.getOverlay().add(mRegionSelectorDrawable);
    }

    /**
     * Scrolls the view if necessary.
     */
    private void scrollViewIfNecessary() {
        mRecyclerView.removeCallbacks(mScrollRunnable);
        mScrollRunnable.run();
        mRecyclerView.invalidate();
    }

    /**
     * Resizes the band select rectangle by using the origin and the current pointer positoin as
     * two opposite corners of the selection.
     */
    private void resizeBandSelectRectangle() {
        if (mBounds != null) {
            mCursorDeltaY = mPointer.y - mBounds.bottom;
        }

        mBounds = new Rect(Math.min(mOrigin.x, mPointer.x),
                Math.min(mOrigin.y, mPointer.y),
                Math.max(mOrigin.x, mPointer.x),
                Math.max(mOrigin.y, mPointer.y));

        mRegionSelectorDrawable.setBounds(mBounds);
    }

    /**
     * Selects the children covered by the band select overlay by delegating to MultiSelectManager.
     * TODO: Provide a finished implementation. This is down and dirty, proof of concept code.
     * Final optimized implementation, with support for managing offscreen selection to come.
     */
    private void selectChildrenCoveredBySelection() {

        // track top and bottom selections. Details on why this is useful below.
        int first = NOT_SELECTED;
        int last = NOT_SELECTED;

        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {

            View child = mRecyclerView.getChildAt(i);
            ViewHolder holder = mRecyclerView.getChildViewHolder(child);
            Rect childRect = new Rect();
            child.getHitRect(childRect);

            boolean shouldSelect = Rect.intersects(childRect, mBounds);
            int position = holder.getAdapterPosition();

            // This also allows us to clear the selection of elements
            // that only temporarily entered the bounds of the band.
            if (mSelectedByBand.get(position) && !shouldSelect) {
                mSelectManager.setItemSelected(position, false);
                mSelectedByBand.delete(position);
            }

            // We need to keep track of the first and last items selected.
            // We'll use this information along with cursor direction
            // to determine the starting point of the selection.
            // We provide this information to selection manager
            // to enable more natural user interaction when working
            // with Shift+Click and multiple contiguous selection ranges.
            if (shouldSelect) {
                if (first == NOT_SELECTED) {
                    first = position;
                } else {
                    last = position;
                }
                mSelectManager.setItemSelected(position, true);
                mSelectedByBand.put(position, true);
            }
        }

        // Remember which is the last selected item, so we can
        // share that with selection manager when band select ends.
        // It'll use that as it's begin selection point when
        // user SHIFT+Clicks.
        if (mCursorDeltaY < 0 && last != NOT_SELECTED) {
            mFirstSelected = last;
        } else if (mCursorDeltaY > 0 && first != NOT_SELECTED) {
            mFirstSelected = first;
        }
    }

    /**
     * Ends band select by removing the overlay.
     */
    private void endBandSelect() {
        if (DEBUG) Log.d(TAG, "Ending band select.");
        mIsBandSelectActive = false;
        mSelectedByBand.clear();
        mRecyclerView.getOverlay().remove(mRegionSelectorDrawable);
        if (mFirstSelected != NOT_SELECTED) {
            mSelectManager.setSelectionFocusBegin(mFirstSelected);
        }
    }
}
