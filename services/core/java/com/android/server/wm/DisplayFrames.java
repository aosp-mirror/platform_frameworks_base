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

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;
import android.view.DisplayInfo;

import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;

/**
 * Container class for all the display frames that affect how we do window layout on a display.
 * @hide
 */
public class DisplayFrames {
    public final int mDisplayId;

    /**
     * The current visible size of the screen; really; (ir)regardless of whether the status bar can
     * be hidden but not extending into the overscan area.
     */
    public final Rect mUnrestricted = new Rect();

    /** The display cutout used for layout (after rotation) */
    @NonNull public WmDisplayCutout mDisplayCutout = WmDisplayCutout.NO_CUTOUT;

    /** The cutout as supplied by display info */
    @NonNull public WmDisplayCutout mDisplayInfoCutout = WmDisplayCutout.NO_CUTOUT;

    /**
     * During layout, the frame that is display-cutout safe, i.e. that does not intersect with it.
     */
    public final Rect mDisplayCutoutSafe = new Rect();

    public int mDisplayWidth;
    public int mDisplayHeight;

    public int mRotation;

    public DisplayFrames(int displayId, DisplayInfo info, WmDisplayCutout displayCutout) {
        mDisplayId = displayId;
        onDisplayInfoUpdated(info, displayCutout);
    }

    public void onDisplayInfoUpdated(DisplayInfo info, WmDisplayCutout displayCutout) {
        mDisplayWidth = info.logicalWidth;
        mDisplayHeight = info.logicalHeight;
        mRotation = info.rotation;
        mDisplayInfoCutout = displayCutout != null ? displayCutout : WmDisplayCutout.NO_CUTOUT;
    }

    public void onBeginLayout() {
        mUnrestricted.set(0, 0, mDisplayWidth, mDisplayHeight);
        mDisplayCutout = mDisplayInfoCutout;
        mDisplayCutoutSafe.set(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (!mDisplayCutout.getDisplayCutout().isEmpty()) {
            final DisplayCutout c = mDisplayCutout.getDisplayCutout();
            if (c.getSafeInsetLeft() > 0) {
                mDisplayCutoutSafe.left = mUnrestricted.left + c.getSafeInsetLeft();
            }
            if (c.getSafeInsetTop() > 0) {
                mDisplayCutoutSafe.top = mUnrestricted.top + c.getSafeInsetTop();
            }
            if (c.getSafeInsetRight() > 0) {
                mDisplayCutoutSafe.right = mUnrestricted.right - c.getSafeInsetRight();
            }
            if (c.getSafeInsetBottom() > 0) {
                mDisplayCutoutSafe.bottom = mUnrestricted.bottom - c.getSafeInsetBottom();
            }
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.end(token);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayFrames w=" + mDisplayWidth + " h=" + mDisplayHeight
                + " r=" + mRotation);
        final String myPrefix = prefix + "  ";
        dumpFrame(mUnrestricted, "mUnrestricted", myPrefix, pw);
        pw.println(myPrefix + "mDisplayCutout=" + mDisplayCutout);
    }

    private void dumpFrame(Rect frame, String name, String prefix, PrintWriter pw) {
        pw.print(prefix + name + "="); frame.printShortString(pw); pw.println();
    }
}
