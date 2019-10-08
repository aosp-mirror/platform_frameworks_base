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

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.hamcrest.Matchers.equalTo;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;

@SmallTest
@Presubmit
public class DisplayPolicyInsetsTests extends DisplayPolicyTestsBase {

    @Rule
    public final ErrorCollector mErrorCollector = new ErrorCollector();

    @Test
    public void portrait() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_0, false /* withCutout */);

        verifyStableInsets(di, 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        verifyNonDecorInsets(di, 0, 0, 0, NAV_BAR_HEIGHT);
        verifyConsistency(di);
    }

    @Test
    public void portrait_withCutout() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_0, true /* withCutout */);

        verifyStableInsets(di, 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        verifyNonDecorInsets(di, 0, DISPLAY_CUTOUT_HEIGHT, 0, NAV_BAR_HEIGHT);
        verifyConsistency(di);
    }

    @Test
    public void landscape() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_90, false /* withCutout */);

        verifyStableInsets(di, 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        verifyNonDecorInsets(di, 0, 0, NAV_BAR_HEIGHT, 0);
        verifyConsistency(di);
    }

    @Test
    public void landscape_withCutout() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_90, true /* withCutout */);

        verifyStableInsets(di, DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        verifyNonDecorInsets(di, DISPLAY_CUTOUT_HEIGHT, 0, NAV_BAR_HEIGHT, 0);
        verifyConsistency(di);
    }

    @Test
    public void seascape() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_270, false /* withCutout */);

        verifyStableInsets(di, NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, 0, 0);
        verifyNonDecorInsets(di, NAV_BAR_HEIGHT, 0, 0, 0);
        verifyConsistency(di);
    }

    @Test
    public void seascape_withCutout() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_270, true /* withCutout */);

        verifyStableInsets(di, NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, DISPLAY_CUTOUT_HEIGHT, 0);
        verifyNonDecorInsets(di, NAV_BAR_HEIGHT, 0, DISPLAY_CUTOUT_HEIGHT, 0);
        verifyConsistency(di);
    }

    @Test
    public void upsideDown() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_180, false /* withCutout */);

        verifyStableInsets(di, 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        verifyNonDecorInsets(di, 0, 0, 0, NAV_BAR_HEIGHT);
        verifyConsistency(di);
    }

    @Test
    public void upsideDown_withCutout() {
        final DisplayInfo di = displayInfoForRotation(ROTATION_180, true /* withCutout */);

        verifyStableInsets(di, 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT + DISPLAY_CUTOUT_HEIGHT);
        verifyNonDecorInsets(di, 0, 0, 0, NAV_BAR_HEIGHT + DISPLAY_CUTOUT_HEIGHT);
        verifyConsistency(di);
    }

    private void verifyStableInsets(DisplayInfo di, int left, int top, int right, int bottom) {
        mErrorCollector.checkThat("stableInsets", getStableInsetsLw(di), equalTo(new Rect(
                left, top, right, bottom)));
    }

    private void verifyNonDecorInsets(DisplayInfo di, int left, int top, int right, int bottom) {
        mErrorCollector.checkThat("nonDecorInsets", getNonDecorInsetsLw(di), equalTo(new Rect(
                left, top, right, bottom)));
    }

    private void verifyConsistency(DisplayInfo di) {
        verifyConsistency("configDisplay", di, getStableInsetsLw(di),
                getConfigDisplayWidth(di), getConfigDisplayHeight(di));
        verifyConsistency("nonDecorDisplay", di, getNonDecorInsetsLw(di),
                getNonDecorDisplayWidth(di), getNonDecorDisplayHeight(di));
    }

    private void verifyConsistency(String what, DisplayInfo di, Rect insets, int width,
            int height) {
        mErrorCollector.checkThat(what + ":width", width,
                equalTo(di.logicalWidth - insets.left - insets.right));
        mErrorCollector.checkThat(what + ":height", height,
                equalTo(di.logicalHeight - insets.top - insets.bottom));
    }

    private Rect getStableInsetsLw(DisplayInfo di) {
        Rect result = new Rect();
        mDisplayPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                di.displayCutout, result);
        return result;
    }

    private Rect getNonDecorInsetsLw(DisplayInfo di) {
        Rect result = new Rect();
        mDisplayPolicy.getNonDecorInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                di.displayCutout, result);
        return result;
    }

    private int getNonDecorDisplayWidth(DisplayInfo di) {
        return mDisplayPolicy.getNonDecorDisplayWidth(di.logicalWidth, di.logicalHeight,
                di.rotation, 0 /* ui */, di.displayCutout);
    }

    private int getNonDecorDisplayHeight(DisplayInfo di) {
        return mDisplayPolicy.getNonDecorDisplayHeight(di.logicalWidth, di.logicalHeight,
                di.rotation, 0 /* ui */, di.displayCutout);
    }

    private int getConfigDisplayWidth(DisplayInfo di) {
        return mDisplayPolicy.getConfigDisplayWidth(di.logicalWidth, di.logicalHeight,
                di.rotation, 0 /* ui */, di.displayCutout);
    }

    private int getConfigDisplayHeight(DisplayInfo di) {
        return mDisplayPolicy.getConfigDisplayHeight(di.logicalWidth, di.logicalHeight,
                di.rotation, 0 /* ui */, di.displayCutout);
    }

    private static DisplayInfo displayInfoForRotation(int rotation, boolean withDisplayCutout) {
        return displayInfoAndCutoutForRotation(rotation, withDisplayCutout).first;
    }
}
