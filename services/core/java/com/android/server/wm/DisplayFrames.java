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

import static android.view.InsetsSource.createId;
import static android.view.WindowInsets.Type.displayCutout;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.DisplayShape;
import android.view.InsetsState;
import android.view.PrivacyIndicatorBounds;
import android.view.RoundedCorners;

import java.io.PrintWriter;

/**
 * Container class for all the display frames that affect how we do window layout on a display.
 * @hide
 */
public class DisplayFrames {

    private static final int ID_DISPLAY_CUTOUT_LEFT = createId(null, 0, displayCutout());
    private static final int ID_DISPLAY_CUTOUT_TOP = createId(null, 1, displayCutout());
    private static final int ID_DISPLAY_CUTOUT_RIGHT = createId(null, 2, displayCutout());
    private static final int ID_DISPLAY_CUTOUT_BOTTOM = createId(null, 3, displayCutout());

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
            RoundedCorners roundedCorners, PrivacyIndicatorBounds indicatorBounds,
            DisplayShape displayShape) {
        mInsetsState = insetsState;
        update(info.rotation, info.logicalWidth, info.logicalHeight, cutout, roundedCorners,
                indicatorBounds, displayShape);
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
            @NonNull PrivacyIndicatorBounds indicatorBounds,
            @NonNull DisplayShape displayShape) {
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
        final Rect u = mUnrestricted;
        u.set(0, 0, w, h);
        state.setDisplayFrame(u);
        state.setDisplayCutout(displayCutout);
        state.setRoundedCorners(roundedCorners);
        state.setPrivacyIndicatorBounds(indicatorBounds);
        state.setDisplayShape(displayShape);
        state.getDisplayCutoutSafe(safe);
        if (safe.left > u.left) {
            state.getOrCreateSource(ID_DISPLAY_CUTOUT_LEFT, displayCutout())
                    .setFrame(u.left, u.top, safe.left, u.bottom)
                    .updateSideHint(u);
        } else {
            state.removeSource(ID_DISPLAY_CUTOUT_LEFT);
        }
        if (safe.top > u.top) {
            state.getOrCreateSource(ID_DISPLAY_CUTOUT_TOP, displayCutout())
                    .setFrame(u.left, u.top, u.right, safe.top)
                    .updateSideHint(u);
        } else {
            state.removeSource(ID_DISPLAY_CUTOUT_TOP);
        }
        if (safe.right < u.right) {
            state.getOrCreateSource(ID_DISPLAY_CUTOUT_RIGHT, displayCutout())
                    .setFrame(safe.right, u.top, u.right, u.bottom)
                    .updateSideHint(u);
        } else {
            state.removeSource(ID_DISPLAY_CUTOUT_RIGHT);
        }
        if (safe.bottom < u.bottom) {
            state.getOrCreateSource(ID_DISPLAY_CUTOUT_BOTTOM, displayCutout())
                    .setFrame(u.left, safe.bottom, u.right, u.bottom)
                    .updateSideHint(u);
        } else {
            state.removeSource(ID_DISPLAY_CUTOUT_BOTTOM);
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
