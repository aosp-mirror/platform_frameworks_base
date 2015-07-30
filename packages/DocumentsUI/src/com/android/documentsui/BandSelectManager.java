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
import static java.lang.String.format;

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

    // For debugging purposes.
    private static final String TAG = "BandSelectManager";
    private static final boolean DEBUG = false;

    private final RecyclerView mRecyclerView;
    private final MultiSelectManager mSelectManager;
    private final Drawable mRegionSelectorDrawable;
    private final SparseBooleanArray mSelectedByBand = new SparseBooleanArray();

    private boolean mIsBandSelectActive = false;
    private Point mOrigin;
    private Rect mBounds;
    // Maintain the last selection made by band, so if bounds shink back, we can unselect
    // the respective items.

    // Track information
    private int mCursorDeltaY = 0;
    private int mFirstSelected = NOT_SELECTED;

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

        Point point = new Point((int) e.getX(), (int) e.getY());
        if (!mIsBandSelectActive) {
            startBandSelect(point);
        }

        resizeBandSelectRectangle(point);
        selectChildrenCoveredBySelection();
    }

    /**
     * Starts band select by adding the drawable to the RecyclerView's overlay.
     * @param origin The starting point of the selection.
     */
    private void startBandSelect(Point origin) {
        if (DEBUG) Log.d(TAG, "Starting band select from (" + origin.x + "," + origin.y + ").");
        mIsBandSelectActive = true;
        mOrigin = origin;
        mRecyclerView.getOverlay().add(mRegionSelectorDrawable);
    }

    /**
     * Resizes the band select rectangle by using the origin and the current pointer positoin as
     * two opposite corners of the selection.
     * @param pointerPosition
     */
    private void resizeBandSelectRectangle(Point pointerPosition) {

        if (mBounds != null) {
            mCursorDeltaY = pointerPosition.y - mBounds.bottom;
        }

        mBounds = new Rect(Math.min(mOrigin.x, pointerPosition.x),
                Math.min(mOrigin.y, pointerPosition.y),
                Math.max(mOrigin.x, pointerPosition.x),
                Math.max(mOrigin.y, pointerPosition.y));

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
