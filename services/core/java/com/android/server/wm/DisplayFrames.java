/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.InsetsState.ITYPE_BOTTOM_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_LEFT_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_RIGHT_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_TOP_DISPLAY_CUTOUT;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.PrivacyIndicatorBounds;
import android.view.RoundedCorners;

import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;

/**
 * Container class for all the display frames that affect how we do window layout on a display.
 * @hide
 */
public class DisplayFrames {
    public final int mDisplayId;

    public final InsetsState mInsetsState;

    /**
     * The current visible size of the screen; really; (ir)regardless of whether the status bar can
     * be hidden but not extending into the overscan area.
     */
    public final Rect mUnrestricted = new Rect();

    /**
     * During layout, the frame that is display-cutout safe, i.e. that does not intersect with it.
     */
    public final Rect mDisplayCutoutSafe = new Rect();

    public int mDisplayWidth;
    public int mDisplayHeight;

    public int mRotation;

    public DisplayFrames(int displayId, InsetsState insetsState, DisplayInfo info,
            WmDisplayCutout displayCutout, RoundedCorners roundedCorners,
            PrivacyIndicatorBounds indicatorBounds) {
        mDisplayId = displayId;
        mInsetsState = insetsState;
        onDisplayInfoUpdated(info, displayCutout, roundedCorners, indicatorBounds);
    }

    /**
     * Update {@link DisplayFrames} when {@link DisplayInfo} is updated.
     *
     * @param info the updated {@link DisplayInfo}.
     * @param displayCutout the updated {@link DisplayCutout}.
     * @param roundedCorners the updated {@link RoundedCorners}.
     * @return {@code true} if the insets state has been changed; {@code false} otherwise.
     */
    public boolean onDisplayInfoUpdated(DisplayInfo info, @NonNull WmDisplayCutout displayCutout,
            @NonNull RoundedCorners roundedCorners,
            @NonNull PrivacyIndicatorBounds indicatorBounds) {
        mRotation = info.rotation;

        final InsetsState state = mInsetsState;
        final Rect safe = mDisplayCutoutSafe;
        final DisplayCutout cutout = displayCutout.getDisplayCutout();
        if (mDisplayWidth == info.logicalWidth && mDisplayHeight == info.logicalHeight
                 && state.getDisplayCutout().equals(cutout)
                && state.getRoundedCorners().equals(roundedCorners)
                && state.getPrivacyIndicatorBounds().equals(indicatorBounds)) {
            return false;
        }
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
        final Rect unrestricted = mUnrestricted;
        unrestricted.set(0, 0, mDisplayWidth, mDisplayHeight);
        safe.set(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        state.setDisplayFrame(unrestricted);
        state.setDisplayCutout(cutout);
        state.setRoundedCorners(roundedCorners);
        state.setPrivacyIndicatorBounds(indicatorBounds);
        if (!cutout.isEmpty()) {
            if (cutout.getSafeInsetLeft() > 0) {
                safe.left = unrestricted.left + cutout.getSafeInsetLeft();
            }
            if (cutout.getSafeInsetTop() > 0) {
                safe.top = unrestricted.top + cutout.getSafeInsetTop();
            }
            if (cutout.getSafeInsetRight() > 0) {
                safe.right = unrestricted.right - cutout.getSafeInsetRight();
            }
            if (cutout.getSafeInsetBottom() > 0) {
                safe.bottom = unrestricted.bottom - cutout.getSafeInsetBottom();
            }
            state.getSource(ITYPE_LEFT_DISPLAY_CUTOUT).setFrame(
                    unrestricted.left, unrestricted.top, safe.left, unrestricted.bottom);
            state.getSource(ITYPE_TOP_DISPLAY_CUTOUT).setFrame(
                    unrestricted.left, unrestricted.top, unrestricted.right, safe.top);
            state.getSource(ITYPE_RIGHT_DISPLAY_CUTOUT).setFrame(
                    safe.right, unrestricted.top, unrestricted.right, unrestricted.bottom);
            state.getSource(ITYPE_BOTTOM_DISPLAY_CUTOUT).setFrame(
                    unrestricted.left, safe.bottom, unrestricted.right, unrestricted.bottom);
        } else {
            state.removeSource(ITYPE_LEFT_DISPLAY_CUTOUT);
            state.removeSource(ITYPE_TOP_DISPLAY_CUTOUT);
            state.removeSource(ITYPE_RIGHT_DISPLAY_CUTOUT);
            state.removeSource(ITYPE_BOTTOM_DISPLAY_CUTOUT);
        }
        return true;
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.end(token);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayFrames w=" + mDisplayWidth + " h=" + mDisplayHeight
                + " r=" + mRotation);
    }
}
