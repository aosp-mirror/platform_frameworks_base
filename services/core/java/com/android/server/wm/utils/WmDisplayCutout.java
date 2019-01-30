/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.utils;

import android.graphics.Rect;
import android.util.Size;
import android.view.DisplayCutout;
import android.view.Gravity;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper for DisplayCutout that also tracks the display size and using this allows (re)calculating
 * safe insets.
 */
public class WmDisplayCutout {

    public static final WmDisplayCutout NO_CUTOUT = new WmDisplayCutout(DisplayCutout.NO_CUTOUT,
            null);

    private final DisplayCutout mInner;
    private final Size mFrameSize;

    public WmDisplayCutout(DisplayCutout inner, Size frameSize) {
        mInner = inner;
        mFrameSize = frameSize;
    }

    public static WmDisplayCutout computeSafeInsets(DisplayCutout inner,
            int displayWidth, int displayHeight) {
        if (inner == DisplayCutout.NO_CUTOUT || inner.isBoundsEmpty()) {
            return NO_CUTOUT;
        }

        final Size displaySize = new Size(displayWidth, displayHeight);
        final Rect safeInsets = computeSafeInsets(displaySize, inner);
        return new WmDisplayCutout(inner.replaceSafeInsets(safeInsets), displaySize);
    }

    /**
     * Insets the reference frame of the cutout in the given directions.
     *
     * @return a copy of this instance which has been inset
     * @hide
     */
    public WmDisplayCutout inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        DisplayCutout newInner = mInner.inset(insetLeft, insetTop, insetRight, insetBottom);

        if (mInner == newInner) {
            return this;
        }

        Size frame = mFrameSize == null ? null : new Size(
                mFrameSize.getWidth() - insetLeft - insetRight,
                mFrameSize.getHeight() - insetTop - insetBottom);

        return new WmDisplayCutout(newInner, frame);
    }

    /**
     * Recalculates the cutout relative to the given reference frame.
     *
     * The safe insets must already have been computed, e.g. with {@link #computeSafeInsets}.
     *
     * @return a copy of this instance with the safe insets recalculated
     * @hide
     */
    public WmDisplayCutout calculateRelativeTo(Rect frame) {
        if (mInner.isEmpty()) {
            return this;
        }
        return inset(frame.left, frame.top,
                mFrameSize.getWidth() - frame.right, mFrameSize.getHeight() - frame.bottom);
    }

    /**
     * Calculates the safe insets relative to the given display size.
     *
     * @return a copy of this instance with the safe insets calculated
     * @hide
     */
    public WmDisplayCutout computeSafeInsets(int width, int height) {
        return computeSafeInsets(mInner, width, height);
    }

    private static Rect computeSafeInsets(Size displaySize, DisplayCutout cutout) {
        if (displaySize.getWidth() < displaySize.getHeight()) {
            final List<Rect> boundingRects = cutout.replaceSafeInsets(
                    new Rect(0, displaySize.getHeight() / 2, 0, displaySize.getHeight() / 2))
                    .getBoundingRects();
            int topInset = findInsetForSide(displaySize, boundingRects, Gravity.TOP);
            int bottomInset = findInsetForSide(displaySize, boundingRects, Gravity.BOTTOM);
            return new Rect(0, topInset, 0, bottomInset);
        } else if (displaySize.getWidth() > displaySize.getHeight()) {
            final List<Rect> boundingRects = cutout.replaceSafeInsets(
                    new Rect(displaySize.getWidth() / 2, 0, displaySize.getWidth() / 2, 0))
                    .getBoundingRects();
            int leftInset = findInsetForSide(displaySize, boundingRects, Gravity.LEFT);
            int right = findInsetForSide(displaySize, boundingRects, Gravity.RIGHT);
            return new Rect(leftInset, 0, right, 0);
        } else {
            throw new UnsupportedOperationException("not implemented: display=" + displaySize +
                    " cutout=" + cutout);
        }
    }

    private static int findInsetForSide(Size display, List<Rect> boundingRects, int gravity) {
        int inset = 0;
        final int size = boundingRects.size();
        for (int i = 0; i < size; i++) {
            Rect boundingRect = boundingRects.get(i);
            switch (gravity) {
                case Gravity.TOP:
                    if (boundingRect.top == 0) {
                        inset = Math.max(inset, boundingRect.bottom);
                    }
                    break;
                case Gravity.BOTTOM:
                    if (boundingRect.bottom == display.getHeight()) {
                        inset = Math.max(inset, display.getHeight() - boundingRect.top);
                    }
                    break;
                case Gravity.LEFT:
                    if (boundingRect.left == 0) {
                        inset = Math.max(inset, boundingRect.right);
                    }
                    break;
                case Gravity.RIGHT:
                    if (boundingRect.right == display.getWidth()) {
                        inset = Math.max(inset, display.getWidth() - boundingRect.left);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("unknown gravity: " + gravity);
            }
        }
        return inset;
    }

    public DisplayCutout getDisplayCutout() {
        return mInner;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WmDisplayCutout)) {
            return false;
        }
        WmDisplayCutout that = (WmDisplayCutout) o;
        return Objects.equals(mInner, that.mInner) &&
                Objects.equals(mFrameSize, that.mFrameSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mInner, mFrameSize);
    }

    @Override
    public String toString() {
        return "WmDisplayCutout{" + mInner + ", mFrameSize=" + mFrameSize + '}';
    }
}
