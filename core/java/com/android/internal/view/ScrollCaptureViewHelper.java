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
import android.annotation.Nullable;
import android.graphics.Rect;
import android.view.View;

interface ScrollCaptureViewHelper<V extends View> {
    int UP = -1;
    int DOWN = 1;

    /**
     * Verifies that the view is still visible and scrollable. If true is returned here, expect a
     * call to {@link #onComputeScrollBounds(View)} to follow.
     *
     * @param view the view being captured
     * @return true if the callback should respond to a request with scroll bounds
     */
    default boolean onAcceptSession(@Nullable V view) {
        return view != null && view.isVisibleToUser()
                && (view.canScrollVertically(UP) || view.canScrollVertically(DOWN));
    }

    /**
     * Given a scroll capture request for a view, adjust the provided rect to cover the scrollable
     * content area. The default implementation returns the padded content area of {@code view}.
     *
     * @param view the view being captured
     */
    default Rect onComputeScrollBounds(@Nullable V view) {
        return new Rect(view.getPaddingLeft(), view.getPaddingTop(),
                view.getWidth() - view.getPaddingRight(),
                view.getHeight() - view.getPaddingBottom());
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
    void onPrepareForStart(@NonNull V view, Rect scrollBounds);

    /**
     * Map the request onto the screen.
     * <p>
     * Given a  rect describing the area to capture, relative to scrollBounds, take actions
     * necessary to bring the content within the rectangle into the visible area of the view if
     * needed and return the resulting rectangle describing the position and bounds of the area
     * which is visible.
     *
     * @param scrollBounds the area in which scrolling content moves, local to the {@code containing
     *                     view}
     * @param requestRect  the area relative to {@code scrollBounds} which describes the location of
     *                     content to capture for the request
     * @return the visible area within scrollBounds of the requested rectangle, return {@code null}
     * in the case of an unrecoverable error condition, to abort the capture process
     */
    Rect onScrollRequested(@NonNull V view, Rect scrollBounds, Rect requestRect);

    /**
     * Restore the target after capture.
     * <p>
     * Put back anything that was changed in {@link #onPrepareForStart(View, Rect)}.
     *
     * @param view the view being captured
     */
    void onPrepareForEnd(@NonNull V view);
}
