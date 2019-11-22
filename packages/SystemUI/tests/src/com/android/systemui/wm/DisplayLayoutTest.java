/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.wm;

import static android.content.res.Configuration.UI_MODE_TYPE_NORMAL;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;

@SmallTest
public class DisplayLayoutTest extends SysuiTestCase {

    @Test
    public void testInsets() {
        Resources res = createResources(40, 50, false, 30, 40);
        // Test empty display, no bars or anything
        DisplayInfo info = createDisplayInfo(1000, 1500, 0, ROTATION_0);
        DisplayLayout dl = new DisplayLayout(info, res, false, false);
        assertEquals(new Rect(0, 0, 0, 0), dl.stableInsets());
        assertEquals(new Rect(0, 0, 0, 0), dl.nonDecorInsets());

        // Test with bars
        dl = new DisplayLayout(info, res, true, true);
        assertEquals(new Rect(0, 40, 0, 50), dl.stableInsets());
        assertEquals(new Rect(0, 0, 0, 50), dl.nonDecorInsets());

        // Test just cutout
        info = createDisplayInfo(1000, 1500, 60, ROTATION_0);
        dl = new DisplayLayout(info, res, false, false);
        assertEquals(new Rect(0, 60, 0, 0), dl.stableInsets());
        assertEquals(new Rect(0, 60, 0, 0), dl.nonDecorInsets());

        // Test with bars and cutout
        dl = new DisplayLayout(info, res, true, true);
        assertEquals(new Rect(0, 60, 0, 50), dl.stableInsets());
        assertEquals(new Rect(0, 60, 0, 50), dl.nonDecorInsets());
    }

    @Test
    public void testRotate() {
        // Basic rotate utility
        Rect testParent = new Rect(0, 0, 1000, 600);
        Rect testInner = new Rect(40, 20, 120, 80);
        Rect testResult = new Rect(testInner);
        DisplayLayout.rotateBounds(testResult, testParent, 1);
        assertEquals(new Rect(20, 880, 80, 960), testResult);
        testResult.set(testInner);
        DisplayLayout.rotateBounds(testResult, testParent, 2);
        assertEquals(new Rect(880, 20, 960, 80), testResult);
        testResult.set(testInner);
        DisplayLayout.rotateBounds(testResult, testParent, 3);
        assertEquals(new Rect(520, 40, 580, 120), testResult);

        Resources res = createResources(40, 50, false, 30, 40);
        DisplayInfo info = createDisplayInfo(1000, 1500, 60, ROTATION_0);
        DisplayLayout dl = new DisplayLayout(info, res, true, true);
        assertEquals(new Rect(0, 60, 0, 50), dl.stableInsets());
        assertEquals(new Rect(0, 60, 0, 50), dl.nonDecorInsets());

        // Rotate to 90
        dl.rotateTo(res, ROTATION_90);
        assertEquals(new Rect(60, 30, 0, 40), dl.stableInsets());
        assertEquals(new Rect(60, 0, 0, 40), dl.nonDecorInsets());

        // Rotate with moving navbar
        res = createResources(40, 50, true, 30, 40);
        dl = new DisplayLayout(info, res, true, true);
        dl.rotateTo(res, ROTATION_270);
        assertEquals(new Rect(40, 30, 60, 0), dl.stableInsets());
        assertEquals(new Rect(40, 0, 60, 0), dl.nonDecorInsets());
    }

    private Resources createResources(
            int navLand, int navPort, boolean navMoves, int statusLand, int statusPort) {
        Configuration cfg = new Configuration();
        cfg.uiMode = UI_MODE_TYPE_NORMAL;
        Resources res = mock(Resources.class);
        doReturn(navLand).when(res).getDimensionPixelSize(
                R.dimen.navigation_bar_height_landscape_car_mode);
        doReturn(navPort).when(res).getDimensionPixelSize(R.dimen.navigation_bar_height_car_mode);
        doReturn(navLand).when(res).getDimensionPixelSize(R.dimen.navigation_bar_width_car_mode);
        doReturn(navLand).when(res).getDimensionPixelSize(R.dimen.navigation_bar_height_landscape);
        doReturn(navPort).when(res).getDimensionPixelSize(R.dimen.navigation_bar_height);
        doReturn(navLand).when(res).getDimensionPixelSize(R.dimen.navigation_bar_width);
        doReturn(navMoves).when(res).getBoolean(R.bool.config_navBarCanMove);
        doReturn(statusLand).when(res).getDimensionPixelSize(R.dimen.status_bar_height_landscape);
        doReturn(statusPort).when(res).getDimensionPixelSize(R.dimen.status_bar_height_portrait);
        doReturn(cfg).when(res).getConfiguration();
        return res;
    }

    private DisplayInfo createDisplayInfo(int width, int height, int cutoutHeight, int rotation) {
        DisplayInfo info = new DisplayInfo();
        info.logicalWidth = width;
        info.logicalHeight = height;
        info.rotation = rotation;
        if (cutoutHeight > 0) {
            info.displayCutout = new DisplayCutout(
                    Insets.of(0, cutoutHeight, 0, 0) /* safeInsets */, null /* boundLeft */,
                    new Rect(width / 2 - cutoutHeight, 0, width / 2 + cutoutHeight,
                            cutoutHeight) /* boundTop */, null /* boundRight */,
                    null /* boundBottom */);
        } else {
            info.displayCutout = DisplayCutout.NO_CUTOUT;
        }
        info.logicalDensityDpi = 300;
        return info;
    }
}
