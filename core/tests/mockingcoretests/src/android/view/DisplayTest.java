/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.view;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

/**
 * Tests for {@link Display}.
 *
 * <p>Build/Install/Run:
 *
 * atest FrameworksMockingCoreTests:android.view.DisplayTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class DisplayTest {

    private static final int APP_WIDTH = 272;
    private static final int APP_HEIGHT = 700;
    // Tablet size device, ROTATION_0 corresponds to portrait.
    private static final int LOGICAL_WIDTH = 700;
    private static final int LOGICAL_HEIGHT = 1800;

    // Bounds of the app when the device is in portrait mode.
    private static Rect sAppBoundsPortrait = buildAppBounds(LOGICAL_WIDTH, LOGICAL_HEIGHT);
    private static Rect sAppBoundsLandscape = buildAppBounds(LOGICAL_HEIGHT, LOGICAL_WIDTH);

    // Bounds of the device.
    private static Rect sDeviceBoundsPortrait = new Rect(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT);
    private static Rect sDeviceBoundsLandscape = new Rect(0, 0, LOGICAL_HEIGHT, LOGICAL_WIDTH);


    private StaticMockitoSession mMockitoSession;

    private DisplayManagerGlobal mDisplayManagerGlobal;
    private Context mApplicationContext;
    private DisplayInfo mDisplayInfo = new DisplayInfo();

    @Before
    public void setupTests() {
        mMockitoSession = mockitoSession()
                .mockStatic(DisplayManagerGlobal.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        // Ensure no adjustments are set before each test.
        mApplicationContext = ApplicationProvider.getApplicationContext();
        mApplicationContext.getResources().getConfiguration().windowConfiguration.setAppBounds(
                null);
        mApplicationContext.getResources().getConfiguration().windowConfiguration.setMaxBounds(
                null);
        mApplicationContext.getResources().getConfiguration().windowConfiguration
                .setDisplayRotation(WindowConfiguration.ROTATION_UNDEFINED);
        mDisplayInfo.rotation = ROTATION_0;

        mDisplayManagerGlobal = mock(DisplayManagerGlobal.class);
        doReturn(mDisplayInfo).when(mDisplayManagerGlobal).getDisplayInfo(anyInt());
    }

    @After
    public void teardownTests() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
        Mockito.framework().clearInlineMocks();
    }

    @Test
    public void testConstructor_defaultDisplayAdjustments_matchesDisplayInfo() {
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        assertThat(display.getDisplayAdjustments()).isEqualTo(
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        DisplayInfo actualDisplayInfo = new DisplayInfo();
        display.getDisplayInfo(actualDisplayInfo);
        verifyDisplayInfo(actualDisplayInfo, mDisplayInfo);
    }

    @Test
    public void testConstructor_defaultResources_matchesDisplayInfo() {
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        assertThat(display.getDisplayAdjustments()).isEqualTo(
                mApplicationContext.getResources().getDisplayAdjustments());
        DisplayInfo actualDisplayInfo = new DisplayInfo();
        display.getDisplayInfo(actualDisplayInfo);
        verifyDisplayInfo(actualDisplayInfo, mDisplayInfo);
    }

    @Test
    public void testGetRotation_defaultDisplayAdjustments_rotationNotAdjusted() {
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        assertThat(display.getRotation()).isEqualTo(ROTATION_0);
    }

    @Test
    public void testGetRotation_resourcesWithOverrideDisplayAdjustments_rotationAdjusted() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN fixed rotation adjustments are rotated, and an override is set.
        setLocalDisplayInConfig(mApplicationContext.getResources(), ROTATION_90);
        // GIVEN display is constructed with default resources.
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN rotation is adjusted since an override is set.
        assertThat(display.getRotation()).isEqualTo(ROTATION_90);
    }

    @Test
    public void testGetRealSize_defaultResourcesPortrait_matchesLogicalSize() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real size matches display orientation.
        verifyRealSizeIsPortrait(display);
    }

    @Test
    public void testGetRealSize_defaultResourcesLandscape_matchesRotatedLogicalSize() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real size matches display orientation.
        verifyRealSizeIsLandscape(display);
    }

    @Test
    public void testGetRealSize_defaultDisplayAdjustmentsPortrait_matchesLogicalSize() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        // THEN real size matches display orientation.
        verifyRealSizeIsPortrait(display);
    }

    @Test
    public void testGetRealSize_defaultDisplayAdjustmentsLandscape_matchesLogicalSize() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        // THEN real size matches display orientation.
        verifyRealSizeIsLandscape(display);
    }

    @Test
    public void testGetRealSize_resourcesWithPortraitOverrideRotation_rotatedLogicalSize() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        // GIVEN fixed rotation adjustments are rotated, and an override is set.
        setLocalDisplayInConfig(mApplicationContext.getResources(), ROTATION_0);
        // GIVEN display is constructed with default resources.
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real size matches app orientation.
        verifyRealSizeIsPortrait(display);
    }

    @Test
    public void testGetRealSize_resourcesWithLandscapeOverrideRotation_rotatedLogicalSize() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN fixed rotation adjustments are rotated, and an override is set.
        setLocalDisplayInConfig(mApplicationContext.getResources(), ROTATION_90);
        // GIVEN display is constructed with default resources.
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real size matches app orientation.
        verifyRealSizeIsLandscape(display);
    }

    @Test
    public void testGetRealSize_resourcesPortraitSandboxed_matchesAppSandboxBounds() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN app is letterboxed.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sAppBoundsPortrait);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real size matches app bounds.
        verifyRealSizeMatchesBounds(display, sAppBoundsPortrait);
    }

    @Test
    public void testGetRealSize_resourcesPortraitSandboxed_matchesDisplayAreaSandboxBounds() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN max bounds reflect DisplayArea size, which is the same size as the display.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sDeviceBoundsPortrait);
        // GIVEN app bounds do not stretch to include the full DisplayArea.
        mApplicationContext.getResources().getConfiguration().windowConfiguration
                .setAppBounds(buildAppBounds(LOGICAL_WIDTH, LOGICAL_HEIGHT - 10));
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches max bounds for the DisplayArea.
        verifyRealSizeMatchesBounds(display, sDeviceBoundsPortrait);
    }

    @Test
    public void testGetRealSize_resourcesLandscapeSandboxed_matchesAppSandboxBounds() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        // GIVEN app is letterboxed.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sAppBoundsLandscape);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real size matches app bounds.
        verifyRealSizeMatchesBounds(display, sAppBoundsLandscape);
    }

    @Test
    public void testGetRealSize_resourcesLandscapeSandboxed_matchesDisplayAreaSandboxBounds() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        // GIVEN max bounds reflect DisplayArea size, which is the same size as the display.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sDeviceBoundsLandscape);
        // GIVEN app bounds do not stretch to include the full DisplayArea.
        mApplicationContext.getResources().getConfiguration().windowConfiguration
                .setAppBounds(buildAppBounds(LOGICAL_HEIGHT, LOGICAL_WIDTH - 10));
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches max bounds for the DisplayArea.
        verifyRealSizeMatchesBounds(display, sDeviceBoundsLandscape);
    }

    @Test
    public void testGetRealMetrics_defaultResourcesPortrait_matchesLogicalSize() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches display orientation.
        verifyRealMetricsIsPortrait(display);
    }

    @Test
    public void testGetRealMetrics_defaultResourcesLandscape_matchesRotatedLogicalSize() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches display orientation.
        verifyRealMetricsIsLandscape(display);
    }

    @Test
    public void testGetRealMetrics_defaultDisplayAdjustmentsPortrait_matchesLogicalSize() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        // THEN real metrics matches display orientation.
        verifyRealMetricsIsPortrait(display);
    }

    @Test
    public void testGetRealMetrics_defaultDisplayAdjustmentsLandscape_matchesLogicalSize() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        // THEN real metrics matches display orientation.
        verifyRealMetricsIsLandscape(display);
    }

    @Test
    public void testGetRealMetrics_resourcesWithPortraitOverrideRotation_rotatedLogicalSize() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        // GIVEN fixed rotation adjustments are rotated with an override.
        setLocalDisplayInConfig(mApplicationContext.getResources(), ROTATION_0);
        // GIVEN display is constructed with default resources.
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches app orientation.
        verifyRealMetricsIsPortrait(display);
    }

    @Test
    public void testGetRealMetrics_resourcesWithLandscapeOverrideRotation_rotatedLogicalSize() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN fixed rotation adjustments are rotated.
        setLocalDisplayInConfig(mApplicationContext.getResources(), ROTATION_90);
        // GIVEN display is constructed with default resources.
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches app orientation.
        verifyRealMetricsIsLandscape(display);
    }

    @Test
    public void testGetRealMetrics_resourcesPortraitSandboxed_matchesAppSandboxBounds() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN app is letterboxed.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sAppBoundsPortrait);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches app bounds.
        verifyRealMetricsMatchesBounds(display, sAppBoundsPortrait);
    }

    @Test
    public void testGetRealMetrics_resourcesPortraitSandboxed_matchesDisplayAreaSandboxBounds() {
        // GIVEN display is not rotated.
        setDisplayInfoPortrait(mDisplayInfo);
        // GIVEN max bounds reflect DisplayArea size, which is the same size as the display.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sDeviceBoundsPortrait);
        // GIVEN app bounds do not stretch to include the full DisplayArea.
        mApplicationContext.getResources().getConfiguration().windowConfiguration
                .setAppBounds(buildAppBounds(LOGICAL_WIDTH, LOGICAL_HEIGHT - 10));
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches max bounds for the DisplayArea.
        verifyRealMetricsMatchesBounds(display, sDeviceBoundsPortrait);
    }

    @Test
    public void testGetRealMetrics_resourcesLandscapeSandboxed_matchesAppSandboxBounds() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        // GIVEN app is letterboxed.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sAppBoundsLandscape);
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches app bounds.
        verifyRealMetricsMatchesBounds(display, sAppBoundsLandscape);
    }

    @Test
    public void testGetRealMetrics_resourcesLandscapeSandboxed_matchesDisplayAreaSandboxBounds() {
        // GIVEN display is rotated.
        setDisplayInfoLandscape(mDisplayInfo);
        // GIVEN max bounds reflect DisplayArea size, which is the same size as the display.
        setMaxBoundsSandboxed(mApplicationContext.getResources(), sDeviceBoundsLandscape);
        // GIVEN app bounds do not stretch to include the full DisplayArea.
        mApplicationContext.getResources().getConfiguration().windowConfiguration
                .setAppBounds(buildAppBounds(LOGICAL_HEIGHT, LOGICAL_WIDTH - 10));
        final Display display = new Display(mDisplayManagerGlobal, DEFAULT_DISPLAY, mDisplayInfo,
                mApplicationContext.getResources());
        // THEN real metrics matches max bounds for the DisplayArea.
        verifyRealMetricsMatchesBounds(display, sDeviceBoundsLandscape);
    }

    // Given rotated display dimensions, calculate the letterboxed app bounds.
    private static Rect buildAppBounds(int displayWidth, int displayHeight) {
        final int midWidth = displayWidth / 2;
        final int left = midWidth - (APP_WIDTH / 2);
        final int right = midWidth + (APP_WIDTH / 2);
        final int midHeight = displayHeight / 2;
        // Coordinate system starts at top left.
        final int top = midHeight - (APP_HEIGHT / 2);
        final int bottom = midHeight + (APP_HEIGHT / 2);
        return new Rect(left, top, right, bottom);
    }

    private static void setDisplayInfoLandscape(DisplayInfo displayInfo) {
        displayInfo.rotation = ROTATION_90;
        // Flip width & height assignment since the device is rotated.
        displayInfo.logicalWidth = LOGICAL_HEIGHT;
        displayInfo.logicalHeight = LOGICAL_WIDTH;
    }

    private static void setDisplayInfoPortrait(DisplayInfo displayInfo) {
        displayInfo.rotation = ROTATION_0;
        displayInfo.logicalWidth = LOGICAL_WIDTH;
        displayInfo.logicalHeight = LOGICAL_HEIGHT;
    }

    /**
     * Set max bounds to be sandboxed to the app bounds, indicating the app is in
     * size compat mode or letterbox.
     */
    private static void setMaxBoundsSandboxed(Resources resources, Rect bounds) {
        resources.getConfiguration().windowConfiguration.setMaxBounds(bounds);
    }

    /**
     * Do not compare entire display info, since it is updated to match display the test is run on.
     */
    private static void verifyDisplayInfo(DisplayInfo actual, DisplayInfo expected) {
        assertThat(actual.displayId).isEqualTo(expected.displayId);
        assertThat(actual.rotation).isEqualTo(expected.rotation);
        assertThat(actual.logicalWidth).isEqualTo(LOGICAL_WIDTH);
        assertThat(actual.logicalHeight).isEqualTo(LOGICAL_HEIGHT);
    }

    private static void verifyRealSizeIsLandscape(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        // Flip the width and height check since the device is rotated.
        assertThat(size).isEqualTo(new Point(LOGICAL_HEIGHT, LOGICAL_WIDTH));
    }

    private static void verifyRealMetricsIsLandscape(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        // Flip the width and height check since the device is rotated.
        assertThat(metrics.widthPixels).isEqualTo(LOGICAL_HEIGHT);
        assertThat(metrics.heightPixels).isEqualTo(LOGICAL_WIDTH);
    }

    private static void verifyRealSizeIsPortrait(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        assertThat(size).isEqualTo(new Point(LOGICAL_WIDTH, LOGICAL_HEIGHT));
    }

    private static void verifyRealMetricsIsPortrait(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        assertThat(metrics.widthPixels).isEqualTo(LOGICAL_WIDTH);
        assertThat(metrics.heightPixels).isEqualTo(LOGICAL_HEIGHT);
    }

    private static void verifyRealSizeMatchesBounds(Display display, Rect bounds) {
        Point size = new Point();
        display.getRealSize(size);
        assertThat(size).isEqualTo(new Point(bounds.width(), bounds.height()));
    }

    private static void verifyRealMetricsMatchesBounds(Display display, Rect bounds) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        assertThat(metrics.widthPixels).isEqualTo(bounds.width());
        assertThat(metrics.heightPixels).isEqualTo(bounds.height());
    }

    private static void setLocalDisplayInConfig(Resources resources,
            @Surface.Rotation int rotation) {
        resources.getConfiguration().windowConfiguration.setDisplayRotation(rotation);
    }
}
