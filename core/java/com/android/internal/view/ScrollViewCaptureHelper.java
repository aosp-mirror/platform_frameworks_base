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
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * ScrollCapture for ScrollView and <i>ScrollView-like</i> ViewGroups.
 * <p>
 * Requirements for proper operation:
 * <ul>
 * <li>contains at most 1 child.
 * <li>scrolls to absolute positions with {@link View#scrollTo(int, int)}.
 * <li>has a finite, known content height and scrolling range
 * <li>correctly implements {@link View#canScrollVertically(int)}
 * <li>correctly implements {@link ViewParent#requestChildRectangleOnScreen(View,
 * Rect, boolean)}
 * </ul>
 *
 * @see ScrollCaptureViewSupport
 */
public class ScrollViewCaptureHelper implements ScrollCaptureViewHelper<ViewGroup> {
    private int mStartScrollY;
    private boolean mScrollBarEnabled;
    private int mOverScrollMode;

    public void onPrepareForStart(@NonNull ViewGroup view, Rect scrollBounds) {
        mStartScrollY = view.getScrollY();
        mOverScrollMode = view.getOverScrollMode();
        if (mOverScrollMode != View.OVER_SCROLL_NEVER) {
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        mScrollBarEnabled = view.isVerticalScrollBarEnabled();
        if (mScrollBarEnabled) {
            view.setVerticalScrollBarEnabled(false);
        }
    }

    public ScrollResult onScrollRequested(@NonNull ViewGroup view, Rect scrollBounds,
            Rect requestRect) {
        /*
               +---------+ <----+ Content [25,25 - 275,1025] (w=250,h=1000)
               |         |
            ...|.........|...  startScrollY=100
               |         |
            +--+---------+---+ <--+ Container View [0,0 - 300,300] (scrollY=200)
            |  .         .   |
        --- |  . +-----+   <------+ Scroll Bounds [50,50 - 250,250] (200x200)
         ^  |  . |     | .   |      (Local to Container View, fixed/un-scrolled)
         |  |  . |     | .   |
         |  |  . |     | .   |
         |  |  . +-----+ .   |
         |  |  .         .   |
         |  +--+---------+---+
         |     |         |
        -+-    | +-----+ |
               | |#####| |   <--+ Requested Bounds [0,300 - 200,400] (200x100)
               | +-----+ |       (Local to Scroll Bounds, fixed/un-scrolled)
               |         |
               +---------+

        Container View (ScrollView) [0,0 - 300,300] (scrollY = 200)
        \__ Content [25,25 - 275,1025]  (250x1000) (contentView)
        \__ Scroll Bounds[50,50 - 250,250]  (w=200,h=200)
            \__ Requested Bounds[0,300 - 200,400] (200x100)
       */

        // 0) adjust the requestRect to account for scroll change since start
        //
        //  Scroll Bounds[50,50 - 250,250]  (w=200,h=200)
        //  \__ Requested Bounds[0,200 - 200,300] (200x100)

        // (y-100) (scrollY - mStartScrollY)
        int scrollDelta = view.getScrollY() - mStartScrollY;

        final ScrollResult result = new ScrollResult();
        result.requestedArea = new Rect(requestRect);
        result.scrollDelta = scrollDelta;
        result.availableArea = new Rect();

        final View contentView = view.getChildAt(0); // returns null, does not throw IOOBE
        if (contentView == null) {
            // No child view? Cannot continue.
            return result;
        }

        //  1) Translate request rect to make it relative to container view
        //
        //  Container View [0,0 - 300,300] (scrollY=200)
        //  \__ Requested Bounds[50,250 - 250,350] (w=250, h=100)

        // (x+50,y+50)
        Rect requestedContainerBounds = new Rect(requestRect);
        requestedContainerBounds.offset(0, -scrollDelta);
        requestedContainerBounds.offset(scrollBounds.left, scrollBounds.top);

        //  2) Translate from container to contentView relative (applying container scrollY)
        //
        //  Container View [0,0 - 300,300] (scrollY=200)
        //  \__ Content [25,25 - 275,1025]  (250x1000) (contentView)
        //      \__ Requested Bounds[25,425 - 200,525] (w=250, h=100)

        // (x-25,y+175) (scrollY - content.top)
        Rect requestedContentBounds = new Rect(requestedContainerBounds);
        requestedContentBounds.offset(
                view.getScrollX() - contentView.getLeft(),
                view.getScrollY() - contentView.getTop());

        Rect input = new Rect(requestedContentBounds);

        // Expand input rect to get the requested rect to be in the center
        int remainingHeight = view.getHeight() - view.getPaddingTop()
                - view.getPaddingBottom() - input.height();
        if (remainingHeight > 0) {
            input.inset(0, -remainingHeight / 2);
        }

        // requestRect is now local to contentView as requestedContentBounds
        // contentView (and each parent in turn if possible) will be scrolled
        // (if necessary) to make all of requestedContent visible, (if possible!)
        contentView.requestRectangleOnScreen(input, true);

        // update new offset between starting and current scroll position
        scrollDelta = view.getScrollY() - mStartScrollY;
        result.scrollDelta = scrollDelta;

        // TODO: crop capture area to avoid occlusions/minimize scroll changes

        Point offset = new Point();
        final Rect available = new Rect(requestedContentBounds);
        if (!view.getChildVisibleRect(contentView, available, offset)) {
            available.setEmpty();
            result.availableArea = available;
            return result;
        }
        // Transform back from global to content-view local
        available.offset(-offset.x, -offset.y);

        // Then back to container view
        available.offset(
                contentView.getLeft() - view.getScrollX(),
                contentView.getTop() - view.getScrollY());


        // And back to relative to scrollBounds
        available.offset(-scrollBounds.left, -scrollBounds.top);

        // Apply scrollDelta again to return to make `available` relative to `scrollBounds` at
        // the scroll position at start of capture.
        available.offset(0, scrollDelta);

        result.availableArea = new Rect(available);
        return result;
    }

    public void onPrepareForEnd(@NonNull ViewGroup view) {
        view.scrollTo(0, mStartScrollY);
        if (mOverScrollMode != View.OVER_SCROLL_NEVER) {
            view.setOverScrollMode(mOverScrollMode);
        }
        if (mScrollBarEnabled) {
            view.setVerticalScrollBarEnabled(true);
        }
    }
}
