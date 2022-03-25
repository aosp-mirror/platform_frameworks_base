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
import android.os.CancellationSignal;
import android.util.Log;
import android.view.View;
import android.widget.ListView;

import java.util.function.Consumer;

/**
 * Scroll capture support for ListView.
 *
 * @see ScrollCaptureViewSupport
 */
public class ListViewCaptureHelper implements ScrollCaptureViewHelper<ListView> {
    private static final String TAG = "LVCaptureHelper";
    private int mScrollDelta;
    private boolean mScrollBarWasEnabled;
    private int mOverScrollMode;

    @Override
    public boolean onAcceptSession(@NonNull ListView view) {
        return view.isVisibleToUser()
                && (view.canScrollVertically(UP) || view.canScrollVertically(DOWN));
    }

    @Override
    public void onPrepareForStart(@NonNull ListView view, Rect scrollBounds) {
        mScrollDelta = 0;

        mOverScrollMode = view.getOverScrollMode();
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);

        mScrollBarWasEnabled = view.isVerticalScrollBarEnabled();
        view.setVerticalScrollBarEnabled(false);
    }

    @Override
    public void onScrollRequested(@NonNull ListView listView, Rect scrollBounds,
            Rect requestRect, CancellationSignal signal, Consumer<ScrollResult> resultConsumer) {
        Log.d(TAG, "-----------------------------------------------------------");
        Log.d(TAG, "onScrollRequested(scrollBounds=" + scrollBounds + ", "
                + "requestRect=" + requestRect + ")");

        ScrollResult result = new ScrollResult();
        result.requestedArea = new Rect(requestRect);
        result.scrollDelta = mScrollDelta;
        result.availableArea = new Rect(); // empty

        if (!listView.isVisibleToUser() || listView.getChildCount() == 0) {
            Log.w(TAG, "listView is empty or not visible, cannot continue");
            resultConsumer.accept(result);  // result.availableArea == empty Rect
            return;
        }

        // Make requestRect relative to RecyclerView (from scrollBounds)
        Rect requestedContainerBounds =
                transformFromRequestToContainer(mScrollDelta, scrollBounds, requestRect);

        Rect recyclerLocalVisible = new Rect();
        listView.getLocalVisibleRect(recyclerLocalVisible);

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

        View refView = findScrollingReferenceView(listView, scrollAmount);
        int refTop = refView.getTop();

        listView.scrollListBy(scrollAmount);
        int scrollDistance = refTop - refView.getTop();
        Log.d(TAG, "Parent view has scrolled vertically by " + scrollDistance + " px");

        mScrollDelta += scrollDistance;
        result.scrollDelta = mScrollDelta;
        if (scrollDistance != 0) {
            Log.d(TAG, "Scroll delta is now " + mScrollDelta + " px");
        }

        // Update, post-scroll
        requestedContainerBounds = new Rect(
                transformFromRequestToContainer(mScrollDelta, scrollBounds, requestRect));

        listView.getLocalVisibleRect(recyclerLocalVisible);
        if (requestedContainerBounds.intersect(recyclerLocalVisible)) {
            result.availableArea = transformFromContainerToRequest(
                    mScrollDelta, scrollBounds, requestedContainerBounds);
        }
        Log.d(TAG, "-----------------------------------------------------------");
        resultConsumer.accept(result);
    }

    @Override
    public void onPrepareForEnd(@NonNull ListView listView) {
        // Restore original position and state
        listView.scrollListBy(-mScrollDelta);
        listView.setOverScrollMode(mOverScrollMode);
        listView.setVerticalScrollBarEnabled(mScrollBarWasEnabled);
    }
}
