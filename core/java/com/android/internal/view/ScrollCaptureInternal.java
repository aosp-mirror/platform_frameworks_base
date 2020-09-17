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

import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.ScrollCaptureCallback;
import android.view.View;
import android.view.ViewGroup;

/**
 * Provides built-in framework level Scroll Capture support for standard scrolling Views.
 */
public class ScrollCaptureInternal {
    private static final String TAG = "ScrollCaptureInternal";

    private static final int UP = -1;
    private static final int DOWN = 1;

    /**
     * Not a ViewGroup, or cannot scroll according to View APIs.
     */
    public static final int TYPE_FIXED = 0;

    /**
     * Slides a single child view using mScrollX/mScrollY.
     */
    public static final int TYPE_SCROLLING = 1;

    /**
     * Slides child views through the viewport by translating their layout positions with {@link
     * View#offsetTopAndBottom(int)}. Manages Child view lifecycle, creating as needed and
     * binding views to data from an adapter. Views are reused whenever possible.
     */
    public static final int TYPE_RECYCLING = 2;

    /**
     * Performs tests on the given View and determines:
     * 1. If scrolling is possible
     * 2. What mechanisms are used for scrolling.
     * <p>
     * This needs to be fast and not alloc memory. It's called on everything in the tree not marked
     * as excluded during scroll capture search.
     */
    public static int detectScrollingType(View view) {
        // Must be a ViewGroup
        if (!(view instanceof ViewGroup)) {
            return TYPE_FIXED;
        }
        // Confirm that it can scroll.
        if (!(view.canScrollVertically(DOWN) || view.canScrollVertically(UP))) {
            // Nothing to scroll here, move along.
            return TYPE_FIXED;
        }
        // ScrollViews accept only a single child.
        if (((ViewGroup) view).getChildCount() > 1) {
            return TYPE_RECYCLING;
        }
        //Because recycling containers don't use scrollY, a non-zero value means Scroll view.
        if (view.getScrollY() != 0) {
            return TYPE_SCROLLING;
        }
        // Since scrollY cannot be negative, this means a Recycling view.
        if (view.canScrollVertically(UP)) {
            return TYPE_RECYCLING;
        }
        // canScrollVertically(UP) == false, getScrollY() == 0, getChildCount() == 1.

        // For Recycling containers, this should be a no-op (RecyclerView logs a warning)
        view.scrollTo(view.getScrollX(), 1);

        // A scrolling container would have moved by 1px.
        if (view.getScrollY() == 1) {
            view.scrollTo(view.getScrollX(), 0);
            return TYPE_SCROLLING;
        }
        return TYPE_RECYCLING;
    }

    /**
     * Creates a scroll capture callback for the given view if possible.
     *
     * @param view             the view to capture
     * @param localVisibleRect the visible area of the given view in local coordinates, as supplied
     *                         by the view parent
     * @param positionInWindow the offset of localVisibleRect within the window
     *
     * @return a new callback or null if the View isn't supported
     */
    @Nullable
    public ScrollCaptureCallback requestCallback(View view, Rect localVisibleRect,
            Point positionInWindow) {
        // Nothing to see here yet.
        int i = detectScrollingType(view);
        switch (i) {
            case TYPE_SCROLLING:
                return new ScrollCaptureViewSupport<>((ViewGroup) view,
                        new ScrollViewCaptureHelper());
        }
        return null;
    }
}
