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
        final Rect safeInsets = DisplayCutout.computeSafeInsets(displayWidth, displayHeight, inner);
        return new WmDisplayCutout(inner.replaceSafeInsets(safeInsets), displaySize);
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
