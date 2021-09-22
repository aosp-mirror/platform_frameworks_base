/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.view;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

/**
 * Provides view-specific handling to ScrollCaptureViewSupport.
 *
 * @param <V> the View subclass
 */
public interface ScrollCaptureViewHelper<V extends View> {
    int UP = -1;
    int DOWN = 1;

    /**
     * Contains the result of a scroll request.
     */
    class ScrollResult {
        /**
         * The area requested in pixels, within {@link #onComputeScrollBounds scroll bounds}, with
         * top/bottom relative to the scroll position at the start of capture.
         */
        public Rect requestedArea;
        /**
         * The area, in pixels of the request which is visible and available for capture. In the
         * same coordinate space as {@link #requestedArea}.
         */
        public Rect availableArea;
        /**
         * The updated scroll delta (the relative distance, in pixels that the scroll position has
         * moved from the starting position since capture started).
         */
        public int scrollDelta; // visible top offset from start

        @Override
        public String toString() {
            return "ScrollResult{"
                    + "requestedArea=" + requestedArea
                    + ", availableArea=" + availableArea
                    + ", scrollDelta=" + scrollDelta
                    + '}';
        }
    }

    /**
     * Verifies that the view is still visible and scrollable. If true is returned here, expect a
     * call to {@link #onComputeScrollBounds(View)} to follow.
     *
     * @param view the view being captured
     * @return true if the callback should respond to a request with scroll bounds
     */
    boolean onAcceptSession(@NonNull V view);

    /**
     * Given a scroll capture request for a view, adjust the provided rect to cover the scrollable
     * content area. The default implementation returns the padded content area of {@code view}.
     *
     * @param view the view being captured
     */
    @NonNull default Rect onComputeScrollBounds(@NonNull V view) {
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        if (view instanceof ViewGroup && ((ViewGroup) view).getClipToPadding()) {
            bounds.inset(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), view.getPaddingBottom());
        }
        return bounds;
    }

    /**
     * Adjust the target for capture.
     * <p>
     * Do not touch anything that may change layout positions or sizes on screen. Anything else may
     * be adjusted as long as it can be reversed in {@link #onPrepareForEnd(View)}.
     *
     * @param view         the view being captured
     * @param scrollBounds the bounds within {@code view} where content scrolls
     */
    void onPrepareForStart(@NonNull V view, @NonNull Rect scrollBounds);

    /**
     * Map the request onto the screen.
     * <p>
     * Given a  rect describing the area to capture, relative to scrollBounds, take actions
     * necessary to bring the content within the rectangle into the visible area of the view if
     * needed and return the resulting rectangle describing the position and bounds of the area
     * which is visible.
     *
     * @param view the view being captured
     * @param scrollBounds the area in which scrolling content moves, local to the {@code containing
     *                     view}
     * @param requestRect  the area relative to {@code scrollBounds} which describes the location of
     *                     content to capture for the request
     * @return the result of the request as a {@link ScrollResult}
     */
    @NonNull
    ScrollResult onScrollRequested(@NonNull V view, @NonNull Rect scrollBounds,
            @NonNull Rect requestRect);

    /**
     * Restore the target after capture.
     * <p>
     * Put back anything that was changed in {@link #onPrepareForStart(View, Rect)}.
     *
     * @param view the view being captured
     */
    void onPrepareForEnd(@NonNull V view);
}
