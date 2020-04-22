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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;

import com.android.internal.util.FastMath;

/**
 * A target collects the set of contextual information for a ScrollCaptureHandler discovered during
 * a {@link View#dispatchScrollCaptureSearch scroll capture search}.
 *
 * @hide
 */
public final class ScrollCaptureTarget {
    private final View mContainingView;
    private final ScrollCaptureCallback mCallback;
    private final Rect mLocalVisibleRect;
    private final Point mPositionInWindow;
    private final int mHint;
    private Rect mScrollBounds;

    private final float[] mTmpFloatArr = new float[2];
    private final Matrix mMatrixViewLocalToWindow = new Matrix();
    private final Rect mTmpRect = new Rect();

    public ScrollCaptureTarget(@NonNull View scrollTarget, @NonNull Rect localVisibleRect,
            @NonNull Point positionInWindow, @NonNull ScrollCaptureCallback callback) {
        mContainingView = scrollTarget;
        mHint = mContainingView.getScrollCaptureHint();
        mCallback = callback;
        mLocalVisibleRect = localVisibleRect;
        mPositionInWindow = positionInWindow;
    }

    /** @return the hint that the {@code containing view} had during the scroll capture search */
    @View.ScrollCaptureHint
    public int getHint() {
        return mHint;
    }

    /** @return the {@link ScrollCaptureCallback} for this target */
    @NonNull
    public ScrollCaptureCallback getCallback() {
        return mCallback;
    }

    /** @return the {@code containing view} for this {@link ScrollCaptureCallback callback} */
    @NonNull
    public View getContainingView() {
        return mContainingView;
    }

    /**
     * Returns the un-clipped, visible bounds of the containing view during the scroll capture
     * search. This is used to determine on-screen area to assist in selecting the primary target.
     *
     * @return the visible bounds of the {@code containing view} in view-local coordinates
     */
    @NonNull
    public Rect getLocalVisibleRect() {
        return mLocalVisibleRect;
    }

    /** @return the position of the {@code containing view} within the window */
    @NonNull
    public Point getPositionInWindow() {
        return mPositionInWindow;
    }

    /** @return the {@code scroll bounds} for this {@link ScrollCaptureCallback callback} */
    @Nullable
    public Rect getScrollBounds() {
        return mScrollBounds;
    }

    /**
     * Sets the scroll bounds rect to the intersection of provided rect and the current bounds of
     * the {@code containing view}.
     */
    public void setScrollBounds(@Nullable Rect scrollBounds) {
        mScrollBounds = Rect.copyOrNull(scrollBounds);
        if (mScrollBounds == null) {
            return;
        }
        if (!mScrollBounds.intersect(0, 0,
                mContainingView.getWidth(), mContainingView.getHeight())) {
            mScrollBounds.setEmpty();
        }
    }

    private static void zero(float[] pointArray) {
        pointArray[0] = 0;
        pointArray[1] = 0;
    }

    private static void roundIntoPoint(Point pointObj, float[] pointArray) {
        pointObj.x = FastMath.round(pointArray[0]);
        pointObj.y = FastMath.round(pointArray[1]);
    }

    /**
     * Refresh the value of {@link #mLocalVisibleRect} and {@link #mPositionInWindow} based on the
     * current state of the {@code containing view}.
     */
    @UiThread
    public void updatePositionInWindow() {
        mMatrixViewLocalToWindow.reset();
        mContainingView.transformMatrixToGlobal(mMatrixViewLocalToWindow);

        zero(mTmpFloatArr);
        mMatrixViewLocalToWindow.mapPoints(mTmpFloatArr);
        roundIntoPoint(mPositionInWindow, mTmpFloatArr);
    }

}
