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

import static com.android.internal.view.ScrollCaptureViewSupport.computeScrollAmount;
import static com.android.internal.view.ScrollCaptureViewSupport.findScrollingReferenceView;
import static com.android.internal.view.ScrollCaptureViewSupport.transformFromContainerToRequest;
import static com.android.internal.view.ScrollCaptureViewSupport.transformFromRequestToContainer;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * ScrollCapture for RecyclerView and <i>RecyclerView-like</i> ViewGroups.
 * <p>
 * Requirements for proper operation:
 * <ul>
 * <li>at least one visible child view</li>
 * <li>scrolls by pixels in response to {@link View#scrollBy(int, int)}.
 * <li>reports ability to scroll with {@link View#canScrollVertically(int)}
 * <li>properly implements {@link ViewParent#requestChildRectangleOnScreen(View, Rect, boolean)}
 * </ul>
 *
 * @see ScrollCaptureViewSupport
 */
public class RecyclerViewCaptureHelper implements ScrollCaptureViewHelper<ViewGroup> {
    private static final String TAG = "RVCaptureHelper";
    private int mScrollDelta;
    private boolean mScrollBarWasEnabled;
    private int mOverScrollMode;

    @Override
    public void onPrepareForStart(@NonNull ViewGroup view, Rect scrollBounds) {
        mScrollDelta = 0;

        mOverScrollMode = view.getOverScrollMode();
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);

        mScrollBarWasEnabled = view.isVerticalScrollBarEnabled();
        view.setVerticalScrollBarEnabled(false);
    }

    @Override
    public ScrollResult onScrollRequested(@NonNull ViewGroup recyclerView, Rect scrollBounds,
            Rect requestRect) {
        Log.d(TAG, "-----------------------------------------------------------");
        Log.d(TAG, "onScrollRequested(scrollBounds=" + scrollBounds + ", "
                + "requestRect=" + requestRect + ")");

        ScrollResult result = new ScrollResult();
        result.requestedArea = new Rect(requestRect);
        result.scrollDelta = mScrollDelta;
        result.availableArea = new Rect(); // empty

        if (!recyclerView.isVisibleToUser() || recyclerView.getChildCount() == 0) {
            Log.w(TAG, "recyclerView is empty or not visible, cannot continue");
            return result; // result.availableArea == empty Rect
        }

        // Make requestRect relative to RecyclerView (from scrollBounds)
        Rect requestedContainerBounds =
                transformFromRequestToContainer(mScrollDelta, scrollBounds, requestRect);

        Rect recyclerLocalVisible = new Rect();
        recyclerView.getLocalVisibleRect(recyclerLocalVisible);

        // Expand request rect match visible bounds to center the requested rect vertically
        Rect adjustedContainerBounds = new Rect(requestedContainerBounds);
        int remainingHeight = recyclerLocalVisible.height() -  requestedContainerBounds.height();
        if (remainingHeight > 0) {
            adjustedContainerBounds.inset(0, -remainingHeight / 2);
        }

        int scrollAmount = computeScrollAmount(recyclerLocalVisible, adjustedContainerBounds);
        if (scrollAmount < 0) {
            Log.d(TAG, "About to scroll UP (content moves down within parent)");
        } else if (scrollAmount > 0) {
            Log.d(TAG, "About to scroll DOWN (content moves up within parent)");
        }
        Log.d(TAG, "scrollAmount: " + scrollAmount);

        View refView = findScrollingReferenceView(recyclerView, scrollAmount);
        int refTop = refView.getTop();

        // Map the request into the child view coords
        Rect requestedContentBounds = new Rect(adjustedContainerBounds);
        recyclerView.offsetRectIntoDescendantCoords(refView, requestedContentBounds);
        Log.d(TAG, "request rect, in child view space = " + requestedContentBounds);

        // Note: requestChildRectangleOnScreen may modify rectangle, must pass pass in a copy here
        Rect request = new Rect(requestedContentBounds);
        recyclerView.requestChildRectangleOnScreen(refView, request, true);

        int scrollDistance = refTop - refView.getTop();
        Log.d(TAG, "Parent view scrolled vertically by " + scrollDistance + " px");

        mScrollDelta += scrollDistance;
        result.scrollDelta = mScrollDelta;
        if (scrollDistance != 0) {
            Log.d(TAG, "Scroll delta is now " + mScrollDelta + " px");
        }

        // Update, post-scroll
        requestedContainerBounds = new Rect(
                transformFromRequestToContainer(mScrollDelta, scrollBounds, requestRect));

        // in case it might have changed (nested scrolling)
        recyclerView.getLocalVisibleRect(recyclerLocalVisible);
        if (requestedContainerBounds.intersect(recyclerLocalVisible)) {
            result.availableArea = transformFromContainerToRequest(
                    mScrollDelta, scrollBounds, requestedContainerBounds);
        }
        Log.d(TAG, "-----------------------------------------------------------");
        return result;
    }

    @Override
    public void onPrepareForEnd(@NonNull ViewGroup view) {
        // Restore original position and state
        view.scrollBy(0, -mScrollDelta);
        view.setOverScrollMode(mOverScrollMode);
        view.setVerticalScrollBarEnabled(mScrollBarWasEnabled);
    }
}
