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

    /**
     * Compute the safe insets according to the given DisplayCutout and the display size.
     *
     * @return return a WmDisplayCutout with calculated safe insets.
     * @hide
     */
    public static WmDisplayCutout computeSafeInsets(
            DisplayCutout inner, int displayWidth, int displayHeight) {
        if (inner == DisplayCutout.NO_CUTOUT) {
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
        if (mFrameSize == null) {
            return this;
        }
        final int insetRight = mFrameSize.getWidth() - frame.right;
        final int insetBottom = mFrameSize.getHeight() - frame.bottom;
        if (frame.left == 0 && frame.top == 0 && insetRight == 0 && insetBottom == 0) {
            return this;
        }
        if (frame.left >= mInner.getSafeInsetLeft()
                && frame.top >= mInner.getSafeInsetTop()
                && insetRight >= mInner.getSafeInsetRight()
                && insetBottom >= mInner.getSafeInsetBottom()) {
            return NO_CUTOUT;
        }
        if (mInner.isEmpty()) {
            return this;
        }
        return inset(frame.left, frame.top, insetRight, insetBottom);
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
        if (displaySize.getWidth() == displaySize.getHeight()) {
            throw new UnsupportedOperationException("not implemented: display=" + displaySize +
                    " cutout=" + cutout);
        }

        int leftInset = Math.max(cutout.getWaterfallInsets().left,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectLeft(), Gravity.LEFT));
        int topInset = Math.max(cutout.getWaterfallInsets().top,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectTop(), Gravity.TOP));
        int rightInset = Math.max(cutout.getWaterfallInsets().right,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectRight(), Gravity.RIGHT));
        int bottomInset = Math.max(cutout.getWaterfallInsets().bottom,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectBottom(),
                        Gravity.BOTTOM));

        return new Rect(leftInset, topInset, rightInset, bottomInset);
    }

    private static int findCutoutInsetForSide(Size display, Rect boundingRect, int gravity) {
        if (boundingRect.isEmpty()) {
            return 0;
        }

        int inset = 0;
        switch (gravity) {
            case Gravity.TOP:
                return Math.max(inset, boundingRect.bottom);
            case Gravity.BOTTOM:
                return Math.max(inset, display.getHeight() - boundingRect.top);
            case Gravity.LEFT:
                return Math.max(inset, boundingRect.right);
            case Gravity.RIGHT:
                return Math.max(inset, display.getWidth() - boundingRect.left);
            default:
                throw new IllegalArgumentException("unknown gravity: " + gravity);
        }
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
