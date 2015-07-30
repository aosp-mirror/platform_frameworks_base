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

import static com.android.internal.util.Preconditions.checkState;

import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Provides mouse driven band-select support when used in conjuction with {@link RecyclerView} and
 * {@link MultiSelectManager}. This class is responsible for rendering the band select overlay and
 * selecting overlaid items via MultiSelectManager.
 */
public class BandSelectManager extends RecyclerView.SimpleOnItemTouchListener {

    // For debugging purposes.
    private static final String TAG = "BandSelectManager";
    private static final boolean DEBUG = false;

    private final RecyclerView mRecyclerView;
    private final MultiSelectManager mSelectManager;
    private final Drawable mRegionSelectorDrawable;

    private boolean mIsBandSelectActive = false;
    private Point mOrigin;
    private Rect mRegionBounds;

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
        mRegionBounds = new Rect(Math.min(mOrigin.x, pointerPosition.x),
                Math.min(mOrigin.y, pointerPosition.y),
                Math.max(mOrigin.x, pointerPosition.x),
                Math.max(mOrigin.y, pointerPosition.y));
        mRegionSelectorDrawable.setBounds(mRegionBounds);
    }

    /**
     * Selects the children covered by the band select overlay by delegating to MultiSelectManager.
     * TODO: Provide a finished implementation. This is down and dirty, proof of concept code.
     * Final optimized implementation, with support for managing offscreen selection to come.
     */
    private void selectChildrenCoveredBySelection() {
        for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
            View child = mRecyclerView.getChildAt(i);
            ViewHolder holder = mRecyclerView.getChildViewHolder(child);
            Rect childRect = new Rect();
            child.getHitRect(childRect);

            boolean doRectsOverlap = Rect.intersects(childRect, mRegionBounds);
            mSelectManager.setItemSelected(holder.getAdapterPosition(), doRectsOverlap);
        }
    }

    /**
     * Ends band select by removing the overlay.
     */
    private void endBandSelect() {
        if (DEBUG) Log.d(TAG, "Ending band select.");
        mIsBandSelectActive = false;
        mRecyclerView.getOverlay().remove(mRegionSelectorDrawable);
    }

    /**
     * Determines whether the provided event was triggered by a mouse (as opposed to a finger or
     * stylus).
     * @param e
     * @return
     */
    private static boolean isMouseEvent(MotionEvent e) {
        return e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
    }
}
