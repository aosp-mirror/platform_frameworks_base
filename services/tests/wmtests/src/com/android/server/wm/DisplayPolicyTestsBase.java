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

package com.android.server.wm;

import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.utils.CoordinateTransforms.transformPhysicalToLogicalCoordinates;

import static org.junit.Assert.assertEquals;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Binder;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.WindowManagerGlobal;

import org.junit.Before;

public class DisplayPolicyTestsBase extends WindowTestsBase {

    static final int DISPLAY_WIDTH = 500;
    static final int DISPLAY_HEIGHT = 1000;

    static final int DISPLAY_CUTOUT_HEIGHT = 8;
    static final int IME_HEIGHT = 415;

    DisplayPolicy mDisplayPolicy;

    @Before
    public void setUpDisplayPolicy() {
        // Disable surface placement because it has no direct relation to layout policy and it also
        // avoids some noises such as the display info is modified, screen frozen, config change.
        mWm.mWindowPlacerLocked.deferLayout();

        mDisplayPolicy = mDisplayContent.getDisplayPolicy();
        spyOn(mDisplayPolicy);
        doReturn(true).when(mDisplayPolicy).hasNavigationBar();
        doReturn(true).when(mDisplayPolicy).hasStatusBar();
        addWindow(mStatusBarWindow);
        addWindow(mNavBarWindow);

        // Update source frame and visibility of insets providers.
        mDisplayContent.getInsetsStateController().onPostLayout();
    }

    void addWindow(WindowState win) {
        mDisplayPolicy.adjustWindowParamsLw(win, win.mAttrs);
        assertEquals(WindowManagerGlobal.ADD_OKAY, mDisplayPolicy.validateAddingWindowLw(
                win.mAttrs, Binder.getCallingPid(), Binder.getCallingUid()));
        mDisplayPolicy.addWindowLw(win, win.mAttrs);
        win.mHasSurface = true;
    }

    DisplayInfo displayInfoAndCutoutForRotation(int rotation, boolean withDisplayCutout,
            boolean isLongEdgeCutout) {
        final DisplayInfo info = mDisplayContent.getDisplayInfo();
        final boolean flippedDimensions = rotation == ROTATION_90 || rotation == ROTATION_270;
        info.logicalWidth = flippedDimensions ? DISPLAY_HEIGHT : DISPLAY_WIDTH;
        info.logicalHeight = flippedDimensions ? DISPLAY_WIDTH : DISPLAY_HEIGHT;
        info.rotation = rotation;
        mDisplayContent.mInitialDisplayCutout = withDisplayCutout
                ? displayCutoutForRotation(ROTATION_0, isLongEdgeCutout)
                : DisplayCutout.NO_CUTOUT;
        info.displayCutout = mDisplayContent.calculateDisplayCutoutForRotation(rotation);
        mDisplayContent.updateBaseDisplayMetrics(DISPLAY_WIDTH, DISPLAY_HEIGHT,
                info.logicalDensityDpi, info.physicalXDpi, info.physicalYDpi);
        return info;
    }

    private static DisplayCutout displayCutoutForRotation(int rotation, boolean isLongEdgeCutout) {
        RectF rectF = new RectF();
        if (isLongEdgeCutout) {
            rectF.set(0, DISPLAY_HEIGHT / 4, DISPLAY_CUTOUT_HEIGHT, DISPLAY_HEIGHT * 3 / 4);
        } else {
            rectF.set(DISPLAY_WIDTH / 4, 0, DISPLAY_WIDTH * 3 / 4, DISPLAY_CUTOUT_HEIGHT);
        }

        final Matrix m = new Matrix();
        transformPhysicalToLogicalCoordinates(rotation, DISPLAY_WIDTH, DISPLAY_HEIGHT, m);
        m.mapRect(rectF);

        int pos = -1;
        switch (rotation) {
            case ROTATION_0:
                pos = isLongEdgeCutout ? BOUNDS_POSITION_LEFT : BOUNDS_POSITION_TOP;
                break;
            case ROTATION_90:
                pos = isLongEdgeCutout ? BOUNDS_POSITION_BOTTOM : BOUNDS_POSITION_LEFT;
                break;
            case ROTATION_180:
                pos = isLongEdgeCutout ? BOUNDS_POSITION_RIGHT : BOUNDS_POSITION_BOTTOM;
                break;
            case ROTATION_270:
                pos = isLongEdgeCutout ? BOUNDS_POSITION_TOP : BOUNDS_POSITION_RIGHT;
                break;
        }

        return DisplayCutout.fromBoundingRect((int) rectF.left, (int) rectF.top,
                (int) rectF.right, (int) rectF.bottom, pos);
    }
}
