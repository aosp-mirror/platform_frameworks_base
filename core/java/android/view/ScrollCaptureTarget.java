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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;

import com.android.internal.util.FastMath;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * A target collects the set of contextual information for a ScrollCaptureHandler discovered during
 * a {@link View#dispatchScrollCaptureSearch scroll capture search}.
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

    public ScrollCaptureTarget(@NonNull View scrollTarget, @NonNull Rect localVisibleRect,
            @NonNull Point positionInWindow, @NonNull ScrollCaptureCallback callback) {
        mContainingView = requireNonNull(scrollTarget);
        mHint = mContainingView.getScrollCaptureHint();
        mCallback = requireNonNull(callback);
        mLocalVisibleRect = requireNonNull(localVisibleRect);
        mPositionInWindow = requireNonNull(positionInWindow);
    }

    /**
     * @return the hint that the {@code containing view} had during the scroll capture search
     * @see View#getScrollCaptureHint()
     */
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
     * Returns the visible bounds of the containing view.
     *
     * @return the visible bounds of the {@code containing view} in view-local coordinates
     */
    @NonNull
    public Rect getLocalVisibleRect() {
        return mLocalVisibleRect;
    }

    /** @return the position of the visible bounds of the containing view within the window */
    @NonNull
    public Point getPositionInWindow() {
        return mPositionInWindow;
    }

    /**
     * @return the {@code scroll bounds} for this {@link ScrollCaptureCallback callback}
     *
     * @see ScrollCaptureCallback#onScrollCaptureSearch(CancellationSignal, Consumer)
     */
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
     * Refresh the local visible bounds and it's offset within the window, based on the current
     * state of the {@code containing view}.
     */
    @UiThread
    public void updatePositionInWindow() {
        mMatrixViewLocalToWindow.reset();
        mContainingView.transformMatrixToGlobal(mMatrixViewLocalToWindow);

        zero(mTmpFloatArr);
        mMatrixViewLocalToWindow.mapPoints(mTmpFloatArr);
        roundIntoPoint(mPositionInWindow, mTmpFloatArr);
    }

    public String toString() {
        return "ScrollCaptureTarget{" + "view=" + mContainingView
                + ", callback=" + mCallback
                + ", scrollBounds=" + mScrollBounds
                + ", localVisibleRect=" + mLocalVisibleRect
                + ", positionInWindow=" + mPositionInWindow
                + "}";
    }

    void dump(@NonNull PrintWriter writer) {
        View view = getContainingView();
        writer.println("view: " + view);
        writer.println("hint: " + mHint);
        writer.println("callback: " + mCallback);
        writer.println("scrollBounds: "
                + (mScrollBounds == null ? "null" : mScrollBounds.toShortString()));
        Point inWindow = getPositionInWindow();
        writer.println("positionInWindow: "
                + ((inWindow == null) ? "null" : "[" + inWindow.x + "," + inWindow.y + "]"));
        Rect localVisible = getLocalVisibleRect();
        writer.println("localVisibleRect: "
                + (localVisible == null ? "null" : localVisible.toShortString()));
    }
}
