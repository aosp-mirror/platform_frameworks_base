/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.scroll;

import static android.util.MathUtils.constrain;

import static com.google.common.util.concurrent.Futures.immediateFuture;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.util.Log;

import com.android.systemui.screenshot.scroll.ScrollCaptureClient;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A flexible test double for {@link ScrollCaptureClient.Session}.
 * <p>
 * FakeSession provides the ability to emulate both the available scrollable content range as well
 * as the current visible bounds. Visible bounds may vary because the target view itself may be
 * slid vertically during capture, with portions may become clipped by parent views. This scenario
 * frequently occurs with UIs constructed from nested scrolling views or collapsing headers.
 */
class FakeSession implements ScrollCaptureClient.Session {
    private static final String TAG = "FakeSession";
    // Available range of content
    private final Rect mAvailable;

    /** bounds for scrollDelta (y), range with bottom adjusted to account for page height. */
    private final Rect mAvailableTop;

    private final Rect mVisiblePage;
    private final int mTileHeight;
    private final int mMaxTiles;

    private int mScrollDelta;
    private int mPageHeight;
    private int mTargetHeight;

    FakeSession(int pageHeight, float maxPages, int tileHeight, int visiblePageTop,
            int visiblePageBottom, int availableTop, int availableBottom,
            int maxTiles) {
        mPageHeight = pageHeight;
        mTileHeight = tileHeight;
        mAvailable = new Rect(0, availableTop, getPageWidth(), availableBottom);
        mAvailableTop = new Rect(mAvailable);
        mAvailableTop.inset(0, 0, 0, pageHeight);
        mVisiblePage = new Rect(0, visiblePageTop, getPageWidth(), visiblePageBottom);
        mTargetHeight = (int) (pageHeight * maxPages);
        mMaxTiles = maxTiles;
    }

    private static Image mockImage() {
        Image image = mock(Image.class);
        when(image.getHardwareBuffer()).thenReturn(mock(HardwareBuffer.class));
        return image;
    }

    public int getScrollDelta() {
        return mScrollDelta;
    }

    @Override
    public ListenableFuture<ScrollCaptureClient.CaptureResult> requestTile(int requestedTop) {
        Rect requested = new Rect(0, requestedTop, getPageWidth(), requestedTop + getTileHeight());
        Log.d(TAG, "requested: " + requested);
        Rect page = new Rect(0, 0, getPageWidth(), mPageHeight);
        page.offset(0, mScrollDelta);
        Log.d(TAG, "page: " + page);
        // Simulate behavior from lower levels by replicating 'requestChildRectangleOnScreen'
        if (!page.contains(requested)) {
            Log.d(TAG, "requested not within page, scrolling");
            // distance+direction needed to scroll to align each edge of request with
            // corresponding edge of the page
            int distTop = requested.top - page.top; // positive means already visible
            int distBottom = requested.bottom - page.bottom; // negative means already visible
            Log.d(TAG, "distTop = " + distTop);
            Log.d(TAG, "distBottom = " + distBottom);

            boolean scrollUp = false;
            if (distTop < 0  && distBottom > 0) {
                scrollUp = abs(distTop) < distBottom;
            } else if (distTop < 0) {
                scrollUp = true;
            }

            // determine which edges are currently clipped
            if (scrollUp) {
                Log.d(TAG, "trying to scroll up by " + -distTop + " px");
                // need to scroll up to align top edge to visible-top
                mScrollDelta += distTop;
                Log.d(TAG, "new scrollDelta = " + mScrollDelta);
            } else {
                Log.d(TAG, "trying to scroll down by " + distBottom + " px");
                // scroll down to align bottom edge with visible bottom, but keep top visible
                int topEdgeDistance = max(0, requestedTop - page.top);
                mScrollDelta += min(distBottom, topEdgeDistance);
                Log.d(TAG, "new scrollDelta = " + mScrollDelta);
            }

            // Clamp to available content
            mScrollDelta = constrain(mScrollDelta, mAvailableTop.top, mAvailableTop.bottom);
            Log.d(TAG, "scrollDelta, adjusted to available range = " + mScrollDelta);

            // Reset to apply a changed scroll delta possibly.
            page.offsetTo(0, 0);
            page.offset(0, mScrollDelta);

            Log.d(TAG, "page (after scroll): " + page);
            Log.d(TAG, "requested (after scroll): " + requested);
        }
        Log.d(TAG, "mVisiblePage = " + mVisiblePage);
        Log.d(TAG, "scrollDelta = " + mScrollDelta);

        Rect target = new Rect(requested);
        Rect visible = new Rect(mVisiblePage);
        visible.offset(0, mScrollDelta);

        Log.d(TAG, "target:  " + target);
        Log.d(TAG, "visible:  " + visible);

        // if any of the requested rect is available to scroll into the view:
        if (target.intersect(page) && target.intersect(visible)) {
            Log.d(TAG, "returning captured = " + target);
            ScrollCaptureClient.CaptureResult result =
                    new ScrollCaptureClient.CaptureResult(mockImage(), requested, target);
            return immediateFuture(result);
        }
        Log.d(TAG, "no part of requested rect is within page, returning empty");
        ScrollCaptureClient.CaptureResult result =
                new ScrollCaptureClient.CaptureResult(null, requested, new Rect());
        return immediateFuture(result);
    }


    @Override
    public int getMaxTiles() {
        return mMaxTiles;
    }

    @Override
    public int getTargetHeight() {
        return mTargetHeight;
    }

    @Override
    public int getTileHeight() {
        return mTileHeight;
    }

    @Override
    public int getPageWidth() {
        return 100;
    }

    @Override
    public int getPageHeight() {
        return mPageHeight;
    }

    @Override
    public Rect getWindowBounds() {
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public ListenableFuture<Void> end() {
        return Futures.immediateVoidFuture();
    }

    @Override
    public void release() {
    }
}
