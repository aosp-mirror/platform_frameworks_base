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
        ScrollResult result = new ScrollResult();
        result.requestedArea = new Rect(requestRect);
        result.scrollDelta = mScrollDelta;
        result.availableArea = new Rect(); // empty

        Log.d(TAG, "current scrollDelta: " + mScrollDelta);
        if (!recyclerView.isVisibleToUser() || recyclerView.getChildCount() == 0) {
            Log.w(TAG, "recyclerView is empty or not visible, cannot continue");
            return result; // result.availableArea == empty Rect
        }

        // move from scrollBounds-relative to parent-local coordinates
        Rect requestedContainerBounds = new Rect(requestRect);
        requestedContainerBounds.offset(0, -mScrollDelta);
        requestedContainerBounds.offset(scrollBounds.left, scrollBounds.top);

        // requestedContainerBounds is now in recyclerview-local coordinates
        Log.d(TAG, "requestedContainerBounds: " + requestedContainerBounds);

        // Save a copy for later
        View anchor = findChildNearestTarget(recyclerView, requestedContainerBounds);
        if (anchor == null) {
            Log.d(TAG, "Failed to locate anchor view");
            return result; // result.availableArea == null
        }

        Log.d(TAG, "Anchor view:" + anchor);
        Rect requestedContentBounds = new Rect(requestedContainerBounds);
        recyclerView.offsetRectIntoDescendantCoords(anchor, requestedContentBounds);

        Log.d(TAG, "requestedContentBounds = " + requestedContentBounds);
        int prevAnchorTop = anchor.getTop();
        // Note: requestChildRectangleOnScreen may modify rectangle, must pass pass in a copy here
        Rect input = new Rect(requestedContentBounds);
        // Expand input rect to get the requested rect to be in the center
        int remainingHeight = recyclerView.getHeight() - recyclerView.getPaddingTop()
                - recyclerView.getPaddingBottom() - input.height();
        if (remainingHeight > 0) {
            input.inset(0, -remainingHeight / 2);
        }
        Log.d(TAG, "input (post center adjustment) = " + input);

        if (recyclerView.requestChildRectangleOnScreen(anchor, input, true)) {
            int scrolled = prevAnchorTop - anchor.getTop(); // inverse of movement
            Log.d(TAG, "RecyclerView scrolled by " + scrolled + " px");
            mScrollDelta += scrolled; // view.top-- is equivalent to parent.scrollY++
            result.scrollDelta = mScrollDelta;
            Log.d(TAG, "requestedContentBounds, (post-request-rect) = " + requestedContentBounds);
        }

        requestedContainerBounds.set(requestedContentBounds);
        recyclerView.offsetDescendantRectToMyCoords(anchor, requestedContainerBounds);
        Log.d(TAG, "requestedContainerBounds, (post-scroll): " + requestedContainerBounds);

        Rect recyclerLocalVisible = new Rect(scrollBounds);
        recyclerView.getLocalVisibleRect(recyclerLocalVisible);
        Log.d(TAG, "recyclerLocalVisible: " + recyclerLocalVisible);

        if (!requestedContainerBounds.intersect(recyclerLocalVisible)) {
            // Requested area is still not visible
            Log.d(TAG, "requested bounds not visible!");
            return result;
        }
        Rect available = new Rect(requestedContainerBounds);
        available.offset(-scrollBounds.left, -scrollBounds.top);
        available.offset(0, mScrollDelta);
        result.availableArea = available;
        Log.d(TAG, "availableArea: " + result.availableArea);
        return result;
    }

    /**
     * Find a view that is located "closest" to targetRect. Returns the first view to fully
     * vertically overlap the target targetRect. If none found, returns the view with an edge
     * nearest the target targetRect.
     *
     * @param parent the parent vertical layout
     * @param targetRect a rectangle in local coordinates of <code>parent</code>
     * @return a child view within parent matching the criteria or null
     */
    static View findChildNearestTarget(ViewGroup parent, Rect targetRect) {
        View selected = null;
        int minCenterDistance = Integer.MAX_VALUE;
        int maxOverlap = 0;

        // allowable center-center distance, relative to targetRect.
        // if within this range, taller views are preferred
        final float preferredRangeFromCenterPercent = 0.25f;
        final int preferredDistance =
                (int) (preferredRangeFromCenterPercent * targetRect.height());

        Rect parentLocalVis = new Rect();
        parent.getLocalVisibleRect(parentLocalVis);
        Log.d(TAG, "findChildNearestTarget: parentVis=" + parentLocalVis
                + " targetRect=" + targetRect);

        Rect frame = new Rect();
        for (int i = 0; i < parent.getChildCount(); i++) {
            final View child = parent.getChildAt(i);
            child.getHitRect(frame);
            Log.d(TAG, "child #" + i + " hitRect=" + frame);

            if (child.getVisibility() != View.VISIBLE) {
                Log.d(TAG, "child #" + i + " is not visible");
                continue;
            }

            int centerDistance = Math.abs(targetRect.centerY() - frame.centerY());
            Log.d(TAG, "child #" + i + " : center to center: " + centerDistance + "px");

            if (centerDistance < minCenterDistance) {
                // closer to center
                minCenterDistance = centerDistance;
                selected = child;
            } else if (frame.intersect(targetRect) && (frame.height() > preferredDistance)) {
                // within X% pixels of center, but taller
                selected = child;
            }
        }
        return selected;
    }

    @Override
    public void onPrepareForEnd(@NonNull ViewGroup view) {
        // Restore original position and state
        view.scrollBy(0, -mScrollDelta);
        view.setOverScrollMode(mOverScrollMode);
        view.setVerticalScrollBarEnabled(mScrollBarWasEnabled);
    }
}
