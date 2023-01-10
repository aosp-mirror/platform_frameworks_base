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

import java.io.PrintWriter;

/**
 * Container class for all the display frames that affect how we do window layout on a display.
 * @hide
 */
public class DisplayFrames {
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

    public int mWidth;
    public int mHeight;

    public int mRotation;

    public DisplayFrames(InsetsState insetsState, DisplayInfo info, DisplayCutout cutout,
            RoundedCorners roundedCorners, PrivacyIndicatorBounds indicatorBounds) {
        mInsetsState = insetsState;
        update(info.rotation, info.logicalWidth, info.logicalHeight, cutout, roundedCorners,
                indicatorBounds);
    }

    DisplayFrames() {
        mInsetsState = new InsetsState();
    }

    /**
     * This is called if the display info may be changed, e.g. rotation, size, insets.
     *
     * @return {@code true} if anything has been changed; {@code false} otherwise.
     */
    public boolean update(int rotation, int w, int h, @NonNull DisplayCutout displayCutout,
            @NonNull RoundedCorners roundedCorners,
            @NonNull PrivacyIndicatorBounds indicatorBounds) {
        final InsetsState state = mInsetsState;
        final Rect safe = mDisplayCutoutSafe;
        if (mRotation == rotation && mWidth == w && mHeight == h
                && mInsetsState.getDisplayCutout().equals(displayCutout)
                && state.getRoundedCorners().equals(roundedCorners)
                && state.getPrivacyIndicatorBounds().equals(indicatorBounds)) {
            return false;
        }
        mRotation = rotation;
        mWidth = w;
        mHeight = h;
        final Rect unrestricted = mUnrestricted;
        unrestricted.set(0, 0, w, h);
        state.setDisplayFrame(unrestricted);
        state.setDisplayCutout(displayCutout);
        state.setRoundedCorners(roundedCorners);
        state.setPrivacyIndicatorBounds(indicatorBounds);
        state.getDisplayCutoutSafe(safe);
        if (safe.left > unrestricted.left) {
            state.getSource(ITYPE_LEFT_DISPLAY_CUTOUT).setFrame(
                    unrestricted.left, unrestricted.top, safe.left, unrestricted.bottom);
        } else {
            state.removeSource(ITYPE_LEFT_DISPLAY_CUTOUT);
        }
        if (safe.top > unrestricted.top) {
            state.getSource(ITYPE_TOP_DISPLAY_CUTOUT).setFrame(
                    unrestricted.left, unrestricted.top, unrestricted.right, safe.top);
        } else {
            state.removeSource(ITYPE_TOP_DISPLAY_CUTOUT);
        }
        if (safe.right < unrestricted.right) {
            state.getSource(ITYPE_RIGHT_DISPLAY_CUTOUT).setFrame(
                    safe.right, unrestricted.top, unrestricted.right, unrestricted.bottom);
        } else {
            state.removeSource(ITYPE_RIGHT_DISPLAY_CUTOUT);
        }
        if (safe.bottom < unrestricted.bottom) {
            state.getSource(ITYPE_BOTTOM_DISPLAY_CUTOUT).setFrame(
                    unrestricted.left, safe.bottom, unrestricted.right, unrestricted.bottom);
        } else {
            state.removeSource(ITYPE_BOTTOM_DISPLAY_CUTOUT);
        }
        return true;
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.end(token);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayFrames w=" + mWidth + " h=" + mHeight + " r=" + mRotation);
    }
}
