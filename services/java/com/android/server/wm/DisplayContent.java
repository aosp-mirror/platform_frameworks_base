/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.view.DisplayInfo;

import com.android.server.display.DisplayManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;

class DisplayContentList extends ArrayList<DisplayContent> {
}

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 *
 * IMPORTANT: No method from this class should ever be used without holding
 * WindowManagerService.mWindowMap.
 */
class DisplayContent {

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    /** Z-ordered (bottom-most first) list of all Window objects. Assigned to an element
     * from mDisplayWindows; */
    private WindowList mWindows = new WindowList();


    // This protects the following display size properties, so that
    // getDisplaySize() doesn't need to acquire the global lock.  This is
    // needed because the window manager sometimes needs to use ActivityThread
    // while it has its global state locked (for example to load animation
    // resources), but the ActivityThread also needs get the current display
    // size sometimes when it has its package lock held.
    //
    // These will only be modified with both mWindowMap and mDisplaySizeLock
    // held (in that order) so the window manager doesn't need to acquire this
    // lock when needing these values in its normal operation.
    final Object mDisplaySizeLock = new Object();
    int mInitialDisplayWidth = 0;
    int mInitialDisplayHeight = 0;
    int mInitialDisplayDensity = 0;
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    int mBaseDisplayDensity = 0;
    final DisplayManagerService mDisplayManager;
    final DisplayInfo mDisplayInfo = new DisplayInfo();

    DisplayContent(DisplayManagerService displayManager, final int displayId) {
        mDisplayManager = displayManager;
        mDisplayId = displayId;
        displayManager.getDisplayInfo(displayId, mDisplayInfo);
    }

    int getDisplayId() {
        return mDisplayId;
    }

    WindowList getWindowList() {
        return mWindows;
    }

    DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    public void dump(PrintWriter pw) {
        pw.print("  Display: mDisplayId="); pw.println(mDisplayId);
        pw.print("  init="); pw.print(mInitialDisplayWidth); pw.print("x");
        pw.print(mInitialDisplayHeight); pw.print(" "); pw.print(mInitialDisplayDensity);
        pw.print("dpi");
        if (mInitialDisplayWidth != mBaseDisplayWidth
                || mInitialDisplayHeight != mBaseDisplayHeight
                || mInitialDisplayDensity != mBaseDisplayDensity) {
            pw.print(" base=");
            pw.print(mBaseDisplayWidth); pw.print("x"); pw.print(mBaseDisplayHeight);
            pw.print(" "); pw.print(mBaseDisplayDensity); pw.print("dpi");
        }
        pw.print(" cur=");
        pw.print(mDisplayInfo.logicalWidth);
        pw.print("x"); pw.print(mDisplayInfo.logicalHeight);
        pw.print(" app=");
        pw.print(mDisplayInfo.appWidth);
        pw.print("x"); pw.print(mDisplayInfo.appHeight);
        pw.print(" rng="); pw.print(mDisplayInfo.smallestNominalAppWidth);
        pw.print("x"); pw.print(mDisplayInfo.smallestNominalAppHeight);
        pw.print("-"); pw.print(mDisplayInfo.largestNominalAppWidth);
        pw.print("x"); pw.println(mDisplayInfo.largestNominalAppHeight);
        pw.println();
    }
}
