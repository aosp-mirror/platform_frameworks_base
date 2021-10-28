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

import static android.view.WindowLayout.UNSPECIFIED_LENGTH;

import static org.junit.Assert.assertEquals;

import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.Gravity;
import android.view.InsetsState;
import android.view.InsetsVisibilities;
import android.view.WindowLayout;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link WindowLayout#computeWindowFrames}.
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

    private final WindowLayout mWindowLayout = new WindowLayout();
    private final Rect mDisplayFrame = new Rect();
    private final Rect mParentFrame = new Rect();
    private final Rect mFrame = new Rect();

    private WindowManager.LayoutParams mAttrs;
    private InsetsState mState;
    private final Rect mDisplayCutoutSafe = new Rect();
    private Rect mWindowBounds;
    private int mWindowingMode;
    private int mRequestedWidth;
    private int mRequestedHeight;
    private InsetsVisibilities mRequestedVisibilities;
    private Rect mAttachedWindowFrame;
    private float mCompatScale;

    @Before
    public void setUp() {
        mAttrs = new WindowManager.LayoutParams();
        mState = new InsetsState();
        mState.setDisplayFrame(new Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT));
        mState.getSource(InsetsState.ITYPE_STATUS_BAR).setFrame(
                0, 0, DISPLAY_WIDTH, STATUS_BAR_HEIGHT);
        mState.getSource(InsetsState.ITYPE_NAVIGATION_BAR).setFrame(
                0, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mState.getDisplayCutoutSafe(mDisplayCutoutSafe);
        mWindowBounds = new Rect(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mWindowingMode = WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
        mRequestedWidth = DISPLAY_WIDTH;
        mRequestedHeight = DISPLAY_HEIGHT;
        mRequestedVisibilities = new InsetsVisibilities();
        mAttachedWindowFrame = null;
        mCompatScale = 1f;
    }

    @Test
    public void testDefaultLayoutParams() {
        computeWindowFrames();

        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mDisplayFrame);
        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mParentFrame);
        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mFrame);
    }

    @Test
    public void testUnmeasured() {
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        computeWindowFrames();

        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mDisplayFrame);
        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mParentFrame);
        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mFrame);
    }

    @Test
    public void testUnmeasuredWithSizeSpecifiedInLayoutParams() {
        final int width = DISPLAY_WIDTH / 2;
        final int height = DISPLAY_HEIGHT / 2;
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        mAttrs.width = width;
        mAttrs.height = height;
        mAttrs.gravity = Gravity.LEFT | Gravity.TOP;
        computeWindowFrames();

        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mDisplayFrame);
        assertRect(0, STATUS_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mParentFrame);
        assertRect(0, STATUS_BAR_HEIGHT, width, STATUS_BAR_HEIGHT + height,
                mFrame);
    }

    @Test
    public void testNonFullscreenWindowBounds() {
        final int top = Math.max(DISPLAY_HEIGHT / 2, STATUS_BAR_HEIGHT);
        mWindowBounds.set(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mRequestedWidth = UNSPECIFIED_LENGTH;
        mRequestedHeight = UNSPECIFIED_LENGTH;
        computeWindowFrames();

        assertRect(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mDisplayFrame);
        assertRect(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mParentFrame);
        assertRect(0, top, DISPLAY_WIDTH, DISPLAY_HEIGHT - NAVIGATION_BAR_HEIGHT,
                mFrame);
    }

    // TODO(b/161810301): Move tests here from DisplayPolicyLayoutTests and add more tests.

    private void computeWindowFrames() {
        mWindowLayout.computeWindowFrames(mAttrs, mState, mDisplayCutoutSafe, mWindowBounds,
                mWindowingMode, mRequestedWidth, mRequestedHeight, mRequestedVisibilities,
                mAttachedWindowFrame, mCompatScale, mDisplayFrame, mParentFrame, mFrame);
    }

    private void assertRect(int left, int top, int right, int bottom, Rect actual) {
        assertEquals(new Rect(left, top, right, bottom), actual);
    }
}
