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

package com.android.server.wm;

import static android.view.InsetsState.ITYPE_BOTTOM_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_LEFT_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_RIGHT_DISPLAY_CUTOUT;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.ITYPE_TOP_DISPLAY_CUTOUT;
import static android.view.WindowLayout.UNSPECIFIED_LENGTH;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;

import static org.junit.Assert.assertEquals;

import android.app.WindowConfiguration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.InsetsState;
import android.view.InsetsVisibilities;
import android.view.WindowInsets;
import android.view.WindowLayout;
import android.view.WindowManager;
import android.window.ClientWindowFrames;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link WindowLayout#computeFrames}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowLayoutTests
 */
@SmallTest
@Presubmit
public class WindowLayoutTests {
    private static final int DISPLAY_WIDTH = 500;
    private static final int DISPLAY_HEIGHT = 1000;
    private static final int STATUS_BAR_HEIGHT = 10;
    private static final int NAVIGATION_BAR_HEIGHT = 15;
    private static final int IME_HEIGHT = 400;
    private static final int DISPLAY_CUTOUT_HEIGHT = 8;
    private static final Rect DISPLAY_CUTOUT_BOUNDS_TOP =
            new Rect(DISPLAY_WIDTH / 4, 0, DISPLAY_WIDTH * 3 / 4, DISPLAY_CUTOUT_HEIGHT);
    private static final Insets WATERFALL_INSETS = Insets.of(6, 0, 12, 0);

    private final WindowLayout mWindowLayout = new WindowLayout();
    private final ClientWindowFrames mFrames = new ClientWindowFrames();

    private WindowManager.LayoutParams mAttrs;
    private InsetsState mState;
    private final Rect mDisplayCutoutSafe = new Rect();
    private Rect mWindowBounds;
    private int mWindowingMode;
    private int mRequestedWidth;
    private int mRequestedHeight;
    private InsetsVisibilities mRequestedVisibilities;
    private float mCompatScale;

    @Before
    public void setUp() {
        mAttrs = new WindowManager.LayoutParams();
        mState = new InsetsState();
        mState.setDisplayFrame(new Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT));
        mState.getSource(ITYPE_STATUS_BAR).setFrame(
                0, 0, DISPLAY_WIDTH, STATUS_BAR_HEIGHT);
        mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(
                0, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mState.getDisplayCutoutSafe(mDisplayCutoutSafe);
        mWindowBounds = new Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mWindowingMode = WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        mRequestedWidth = DISPLAY_WIDTH;
        mRequestedHeight = DISPLAY_HEIGHT;
        mRequestedVisibilities = new InsetsVisibilities();
        mCompatScale = 1f;
        mFrames.attachedFrame = null;
    }

    private void computeFrames() {
        mWindowLayout.computeFrames(mAttrs, mState, mDisplayCutoutSafe, mWindowBounds,
                mWindowingMode, mRequestedWidth, mRequestedHeight, mRequestedVisibilities,
                mCompatScale, mFrames);
    }

    private void addDisplayCutout() {
        mState.setDisplayCutout(new DisplayCutout(
                Insets.of(WATERFALL_INSETS.left, DISPLAY_CUTOUT_HEIGHT, WATERFALL_INSETS.right, 0),
                new Rect(),
                DISPLAY_CUTOUT_BOUNDS_TOP,
                new Rect(),
                new Rect(),
                WATERFALL_INSETS));
        mState.getDisplayCutoutSafe(mDisplayCutoutSafe);
        mState.getSource(ITYPE_LEFT_DISPLAY_CUTOUT).setFrame(
                0, 0, mDisplayCutoutSafe.left, DISPLAY_HEIGHT);
        mState.getSource(ITYPE_TOP_DISPLAY_CUTOUT).setFrame(
                0, 0, DISPLAY_WIDTH, mDisplayCutoutSafe.top);
        mState.getSource(ITYPE_RIGHT_DISPLAY_CUTOUT).setFrame(
                mDisplayCutoutSafe.right, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mState.getSource(ITYPE_BOTTOM_DISPLAY_CUTOUT).setFrame(
                0, mDisplayCutoutSafe.bottom, DISPLAY_WIDTH, DISPLAY_HEIGHT);
    }

    private static void assertInsetByTopBottom(int top, int bottom, Rect actual) {
        assertInsetBy(0, top, 0, bottom, actual);
    }

    private static void assertInsetBy(int left, int top, int right, int bottom, Rect actual) {
        assertRect(left, top, DISPLAY_WIDTH - right, DISPLAY_HEIGHT - bottom, actual);
    }

    private static void assertRect(int left, int top, int right, int bottom, Rect actual) {
        assertEquals(new Rect(left, top, right, bottom), actual);
    }

    @Test
    public void defaultParams() {
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.frame);
    }

    @Test
    public void unmeasured() {
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.frame);
    }

    @Test
    public void unmeasuredWithSizeSpecifiedInLayoutParams() {
        final int width = DISPLAY_WIDTH / 2;
        final int height = DISPLAY_HEIGHT / 2;
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        mAttrs.width = width;
        mAttrs.height = height;
        mAttrs.gravity = Gravity.LEFT | Gravity.TOP;
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.parentFrame);
        assertRect(0, STATUS_BAR_HEIGHT, width, STATUS_BAR_HEIGHT + height, mFrames.frame);
    }

    @Test
    public void nonFullscreenWindowBounds() {
        final int top = Math.max(DISPLAY_HEIGHT / 2, STATUS_BAR_HEIGHT);
        mWindowBounds.set(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        computeFrames();

        assertRect(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mFrames.displayFrame);
        assertRect(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mFrames.parentFrame);
        assertRect(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mFrames.frame);
    }

    @Test
    public void attachedFrame() {
        final int bottom = (DISPLAY_HEIGHT - STATUS_BAR_HEIGHT - NAVIGATION_BAR_HEIGHT) / 2;
        mFrames.attachedFrame = new Rect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, bottom);
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertEquals(mFrames.attachedFrame, mFrames.parentFrame);
        assertEquals(mFrames.attachedFrame, mFrames.frame);
    }

    @Test
    public void fitStatusBars() {
        mAttrs.setFitInsetsTypes(WindowInsets.Type.statusBars());
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, 0, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, 0, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, 0, mFrames.frame);
    }

    @Test
    public void fitNavigationBars() {
        mAttrs.setFitInsetsTypes(WindowInsets.Type.navigationBars());
        computeFrames();

        assertInsetByTopBottom(0, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(0, NAVIGATION_BAR_HEIGHT, mFrames.parentFrame);
        assertInsetByTopBottom(0, NAVIGATION_BAR_HEIGHT, mFrames.frame);
    }

    @Test
    public void fitZeroTypes() {
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetByTopBottom(0, 0, mFrames.displayFrame);
        assertInsetByTopBottom(0, 0, mFrames.parentFrame);
        assertInsetByTopBottom(0, 0, mFrames.frame);
    }

    @Test
    public void fitAllSides() {
        mAttrs.setFitInsetsSides(WindowInsets.Side.all());
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.frame);
    }

    @Test
    public void fitTopOnly() {
        mAttrs.setFitInsetsSides(WindowInsets.Side.TOP);
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, 0, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, 0, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, 0, mFrames.frame);
    }

    @Test
    public void fitZeroSides() {
        mAttrs.setFitInsetsSides(0);
        computeFrames();

        assertInsetByTopBottom(0, 0, mFrames.displayFrame);
        assertInsetByTopBottom(0, 0, mFrames.parentFrame);
        assertInsetByTopBottom(0, 0, mFrames.frame);
    }

    @Test
    public void fitInvisibleInsets() {
        mState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(false);
        computeFrames();

        assertInsetByTopBottom(0, 0, mFrames.displayFrame);
        assertInsetByTopBottom(0, 0, mFrames.parentFrame);
        assertInsetByTopBottom(0, 0, mFrames.frame);
    }

    @Test
    public void fitInvisibleInsetsIgnoringVisibility() {
        mState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(false);
        mAttrs.setFitInsetsIgnoringVisibility(true);
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.frame);
    }

    @Test
    public void insetParentFrameByIme() {
        mState.getSource(InsetsState.ITYPE_IME).setVisible(true);
        mState.getSource(InsetsState.ITYPE_IME).setFrame(
                0, DISPLAY_HEIGHT - IME_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mAttrs.privateFlags |= PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
        computeFrames();

        assertInsetByTopBottom(STATUS_BAR_HEIGHT, NAVIGATION_BAR_HEIGHT, mFrames.displayFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, IME_HEIGHT, mFrames.parentFrame);
        assertInsetByTopBottom(STATUS_BAR_HEIGHT, IME_HEIGHT, mFrames.frame);
    }

    @Test
    public void fitDisplayCutout() {
        addDisplayCutout();
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mAttrs.setFitInsetsTypes(WindowInsets.Type.displayCutout());
        computeFrames();

        assertInsetBy(WATERFALL_INSETS.left, DISPLAY_CUTOUT_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.displayFrame);
        assertInsetBy(WATERFALL_INSETS.left, DISPLAY_CUTOUT_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.parentFrame);
        assertInsetBy(WATERFALL_INSETS.left, DISPLAY_CUTOUT_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.frame);
    }

    @Test
    public void layoutInDisplayCutoutModeDefault() {
        addDisplayCutout();
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.displayFrame);
        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.parentFrame);
        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.frame);
    }

    @Test
    public void layoutInDisplayCutoutModeDefaultWithLayoutInScreenAndLayoutInsetDecor() {
        addDisplayCutout();
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        mAttrs.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetBy(WATERFALL_INSETS.left, 0, WATERFALL_INSETS.right, 0, mFrames.displayFrame);
        assertInsetBy(WATERFALL_INSETS.left, 0, WATERFALL_INSETS.right, 0, mFrames.parentFrame);
        assertInsetBy(WATERFALL_INSETS.left, 0, WATERFALL_INSETS.right, 0, mFrames.frame);
    }

    @Test
    public void layoutExtendedToDisplayCutout() {
        addDisplayCutout();
        final int height = DISPLAY_HEIGHT / 2;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        mAttrs.height = height;
        mAttrs.gravity = Gravity.TOP;
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mAttrs.setFitInsetsTypes(0);
        mAttrs.privateFlags |= PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
        computeFrames();

        assertRect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, mFrames.displayFrame);
        assertRect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT, mFrames.parentFrame);
        assertRect(0, 0, DISPLAY_WIDTH, height + DISPLAY_CUTOUT_HEIGHT, mFrames.frame);
    }

    @Test
    public void layoutInDisplayCutoutModeDefaultWithInvisibleSystemBars() {
        addDisplayCutout();
        mState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(false);
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.displayFrame);
        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.parentFrame);
        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.frame);
    }

    @Test
    public void layoutInDisplayCutoutModeShortEdges() {
        addDisplayCutout();
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetBy(WATERFALL_INSETS.left, 0, WATERFALL_INSETS.right, 0, mFrames.displayFrame);
        assertInsetBy(WATERFALL_INSETS.left, 0, WATERFALL_INSETS.right, 0, mFrames.parentFrame);
        assertInsetBy(WATERFALL_INSETS.left, 0, WATERFALL_INSETS.right, 0, mFrames.frame);
    }

    @Test
    public void layoutInDisplayCutoutModeNever() {
        addDisplayCutout();
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.displayFrame);
        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.parentFrame);
        assertInsetBy(WATERFALL_INSETS.left, STATUS_BAR_HEIGHT, WATERFALL_INSETS.right, 0,
                mFrames.frame);
    }

    @Test
    public void layoutInDisplayCutoutModeAlways() {
        addDisplayCutout();
        mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mAttrs.setFitInsetsTypes(0);
        computeFrames();

        assertInsetByTopBottom(0, 0, mFrames.displayFrame);
        assertInsetByTopBottom(0, 0, mFrames.parentFrame);
        assertInsetByTopBottom(0, 0, mFrames.frame);
    }
}
