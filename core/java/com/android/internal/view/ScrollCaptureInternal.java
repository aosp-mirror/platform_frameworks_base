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
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.view.ScrollCaptureCallback;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

/**
 * Provides built-in framework level Scroll Capture support for standard scrolling Views.
 */
public class ScrollCaptureInternal {
    private static final String TAG = "ScrollCaptureInternal";

    // Log found scrolling views
    private static final boolean DEBUG = false;

    // Log all investigated views, as well as heuristic checks
    private static final boolean DEBUG_VERBOSE = false;

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
     * The ViewGroup scrolls, but has no child views in
     */
    private static final int TYPE_OPAQUE = 3;

    /**
     * Performs tests on the given View and determines:
     * 1. If scrolling is possible
     * 2. What mechanisms are used for scrolling.
     * <p>
     * This needs to be fast and not alloc memory. It's called on everything in the tree not marked
     * as excluded during scroll capture search.
     */
    private static int detectScrollingType(View view) {
        // Must be a ViewGroup
        if (!(view instanceof ViewGroup)) {
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "hint: not a subclass of ViewGroup");
            }
            return TYPE_FIXED;
        }
        if (DEBUG_VERBOSE) {
            Log.v(TAG, "hint: is a subclass of ViewGroup");
        }
        // Confirm that it can scroll.
        if (!(view.canScrollVertically(DOWN) || view.canScrollVertically(UP))) {
            // Nothing to scroll here, move along.
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "hint: cannot be scrolled");
            }
            return TYPE_FIXED;
        }
        if (DEBUG_VERBOSE) {
            Log.v(TAG, "hint: can be scrolled up or down");
        }
        // ScrollViews accept only a single child.
        if (((ViewGroup) view).getChildCount() > 1) {
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "hint: scrollable with multiple children");
            }
            return TYPE_RECYCLING;
        }
        // At least one child view is required.
        if (((ViewGroup) view).getChildCount() < 1) {
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "scrollable with no children");
            }
            return TYPE_OPAQUE;
        }
        if (DEBUG_VERBOSE) {
            Log.v(TAG, "hint: single child view");
        }
        //Because recycling containers don't use scrollY, a non-zero value means Scroll view.
        if (view.getScrollY() != 0) {
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "hint: scrollY != 0");
            }
            return TYPE_SCROLLING;
        }
        Log.v(TAG, "hint: scrollY == 0");
        // Since scrollY cannot be negative, this means a Recycling view.
        if (view.canScrollVertically(UP)) {
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "hint: able to scroll up");
            }
            return TYPE_RECYCLING;
        }
        if (DEBUG_VERBOSE) {
            Log.v(TAG, "hint: cannot be scrolled up");
        }

        // canScrollVertically(UP) == false, getScrollY() == 0, getChildCount() == 1.
        // For Recycling containers, this should be a no-op (RecyclerView logs a warning)
        view.scrollTo(view.getScrollX(), 1);

        // A scrolling container would have moved by 1px.
        if (view.getScrollY() == 1) {
            view.scrollTo(view.getScrollX(), 0);
            if (DEBUG_VERBOSE) {
                Log.v(TAG, "hint: scrollTo caused scrollY to change");
            }
            return TYPE_SCROLLING;
        }
        if (DEBUG_VERBOSE) {
            Log.v(TAG, "hint: scrollTo did not cause scrollY to change");
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
     * @return a new callback or null if the View isn't supported
     */
    @Nullable
    public ScrollCaptureCallback requestCallback(View view, Rect localVisibleRect,
            Point positionInWindow) {
        // Nothing to see here yet.
        if (DEBUG_VERBOSE) {
            Log.v(TAG, "scroll capture: checking " + view.getClass().getName()
                    + "[" + resolveId(view.getContext(), view.getId()) + "]");
        }
        int i = detectScrollingType(view);
        switch (i) {
            case TYPE_SCROLLING:
                if (DEBUG) {
                    Log.d(TAG, "scroll capture: FOUND " + view.getClass().getName()
                            + "[" + resolveId(view.getContext(), view.getId()) + "]"
                            + " -> TYPE_SCROLLING");
                }
                return new ScrollCaptureViewSupport<>((ViewGroup) view,
                        new ScrollViewCaptureHelper());
            case TYPE_RECYCLING:
                if (DEBUG) {
                    Log.d(TAG, "scroll capture: FOUND " + view.getClass().getName()
                            + "[" + resolveId(view.getContext(), view.getId()) + "]"
                            + " -> TYPE_RECYCLING");
                }
                if (view instanceof ListView) {
                    // ListView is special.
                    return new ScrollCaptureViewSupport<>((ListView) view,
                            new ListViewCaptureHelper());
                }
                return new ScrollCaptureViewSupport<>((ViewGroup) view,
                        new RecyclerViewCaptureHelper());
            case TYPE_FIXED:
                // ignore
                break;

        }
        return null;
    }

    // Lifted from ViewDebug (package protected)

    private static String formatIntToHexString(int value) {
        return "0x" + Integer.toHexString(value).toUpperCase();
    }

    static String resolveId(Context context, int id) {
        String fieldValue;
        final Resources resources = context.getResources();
        if (id >= 0) {
            try {
                fieldValue = resources.getResourceTypeName(id) + '/'
                        + resources.getResourceEntryName(id);
            } catch (Resources.NotFoundException e) {
                fieldValue = "id/" + formatIntToHexString(id);
            }
        } else {
            fieldValue = "NO_ID";
        }
        return fieldValue;
    }
}
