/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.View.LAYOUT_DIRECTION_LTR;
import static android.view.View.LAYOUT_DIRECTION_RTL;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests operations and the resulting state managed by {@link BubblePositioner}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubblePositionerTest extends ShellTestCase {

    private static final int MIN_WIDTH_FOR_TABLET = 600;

    private BubblePositioner mPositioner;
    private Configuration mConfiguration;

    @Mock
    private WindowManager mWindowManager;
    @Mock
    private WindowMetrics mWindowMetrics;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mConfiguration = spy(new Configuration());
        TestableResources testableResources = mContext.getOrCreateTestableResources();
        testableResources.overrideConfiguration(mConfiguration);

        mPositioner = new BubblePositioner(mContext, mWindowManager);
    }

    @Test
    public void testUpdate() {
        Insets insets = Insets.of(10, 20, 5, 15);
        Rect screenBounds = new Rect(0, 0, 1000, 1200);
        Rect availableRect = new Rect(screenBounds);
        availableRect.inset(insets);

        new WindowManagerConfig()
                .setInsets(insets)
                .setScreenBounds(screenBounds)
                .setUpConfig();
        mPositioner.update();

        assertThat(mPositioner.getAvailableRect()).isEqualTo(availableRect);
        assertThat(mPositioner.isLandscape()).isFalse();
        assertThat(mPositioner.isLargeScreen()).isFalse();
        assertThat(mPositioner.getInsets()).isEqualTo(insets);
    }

    @Test
    public void testShowBubblesVertically_phonePortrait() {
        new WindowManagerConfig().setOrientation(ORIENTATION_PORTRAIT).setUpConfig();
        mPositioner.update();

        assertThat(mPositioner.showBubblesVertically()).isFalse();
    }

    @Test
    public void testShowBubblesVertically_phoneLandscape() {
        new WindowManagerConfig().setOrientation(ORIENTATION_LANDSCAPE).setUpConfig();
        mPositioner.update();

        assertThat(mPositioner.isLandscape()).isTrue();
        assertThat(mPositioner.showBubblesVertically()).isTrue();
    }

    @Test
    public void testShowBubblesVertically_tablet() {
        new WindowManagerConfig().setLargeScreen().setUpConfig();
        mPositioner.update();

        assertThat(mPositioner.showBubblesVertically()).isTrue();
    }

    /** If a resting position hasn't been set, calling it will return the default position. */
    @Test
    public void testGetRestingPosition_returnsDefaultPosition() {
        new WindowManagerConfig().setUpConfig();
        mPositioner.update();

        PointF restingPosition = mPositioner.getRestingPosition();
        PointF defaultPosition = mPositioner.getDefaultStartPosition();

        assertThat(restingPosition).isEqualTo(defaultPosition);
    }

    /** If a resting position has been set, it'll return that instead of the default position. */
    @Test
    public void testGetRestingPosition_returnsRestingPosition() {
        new WindowManagerConfig().setUpConfig();
        mPositioner.update();

        PointF restingPosition = new PointF(100, 100);
        mPositioner.setRestingPosition(restingPosition);

        assertThat(mPositioner.getRestingPosition()).isEqualTo(restingPosition);
    }

    /** Test that the default resting position on phone is in upper left. */
    @Test
    public void testGetRestingPosition_bubble_onPhone() {
        new WindowManagerConfig().setUpConfig();
        mPositioner.update();

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.left);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testGetRestingPosition_bubble_onPhone_RTL() {
        new WindowManagerConfig().setLayoutDirection(LAYOUT_DIRECTION_RTL).setUpConfig();
        mPositioner.update();

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.right);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    /** Test that the default resting position on tablet is middle left. */
    @Test
    public void testGetRestingPosition_chatBubble_onTablet() {
        new WindowManagerConfig().setLargeScreen().setUpConfig();
        mPositioner.update();

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.left);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testGetRestingPosition_chatBubble_onTablet_RTL() {
        new WindowManagerConfig().setLargeScreen().setLayoutDirection(
                LAYOUT_DIRECTION_RTL).setUpConfig();
        mPositioner.update();

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.right);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    /** Test that the default resting position on tablet is middle right. */
    @Test
    public void testGetDefaultPosition_appBubble_onTablet() {
        new WindowManagerConfig().setLargeScreen().setUpConfig();
        mPositioner.update();

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF startPosition = mPositioner.getDefaultStartPosition(true /* isAppBubble */);

        assertThat(startPosition.x).isEqualTo(allowableStackRegion.right);
        assertThat(startPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testGetRestingPosition_appBubble_onTablet_RTL() {
        new WindowManagerConfig().setLargeScreen().setLayoutDirection(
                LAYOUT_DIRECTION_RTL).setUpConfig();
        mPositioner.update();

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF startPosition = mPositioner.getDefaultStartPosition(true /* isAppBubble */);

        assertThat(startPosition.x).isEqualTo(allowableStackRegion.left);
        assertThat(startPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testHasUserModifiedDefaultPosition_false() {
        new WindowManagerConfig().setLargeScreen().setLayoutDirection(
                LAYOUT_DIRECTION_RTL).setUpConfig();
        mPositioner.update();

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isFalse();

        mPositioner.setRestingPosition(mPositioner.getDefaultStartPosition());

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isFalse();
    }

    @Test
    public void testHasUserModifiedDefaultPosition_true() {
        new WindowManagerConfig().setLargeScreen().setLayoutDirection(
                LAYOUT_DIRECTION_RTL).setUpConfig();
        mPositioner.update();

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isFalse();

        mPositioner.setRestingPosition(new PointF(0, 100));

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isTrue();
    }

    @Test
    public void testExpandedViewHeight_onLargeTablet() {
        Insets insets = Insets.of(10, 20, 5, 15);
        Rect screenBounds = new Rect(0, 0, 1800, 2600);

        new WindowManagerConfig()
                .setLargeScreen()
                .setInsets(insets)
                .setScreenBounds(screenBounds)
                .setUpConfig();
        mPositioner.update();

        Intent intent = new Intent(Intent.ACTION_VIEW).setPackage(mContext.getPackageName());
        Bubble bubble = Bubble.createAppBubble(intent, new UserHandle(1), null, directExecutor());

        int manageButtonHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.bubble_manage_button_height);
        float expectedHeight = 1800 - 2 * 20 - manageButtonHeight;
        assertThat(mPositioner.getExpandedViewHeight(bubble)).isWithin(0.1f).of(expectedHeight);
    }

    /**
     * Calculates the Y position bubbles should be placed based on the config. Based on
     * the calculations in {@link BubblePositioner#getDefaultStartPosition()} and
     * {@link BubbleStackView.RelativeStackPosition}.
     */
    private float getDefaultYPosition() {
        final boolean isTablet = mPositioner.isLargeScreen();

        // On tablet the position is centered, on phone it is an offset from the top.
        final float desiredY = isTablet
                ? mPositioner.getScreenRect().height() / 2f - (mPositioner.getBubbleSize() / 2f)
                : mContext.getResources().getDimensionPixelOffset(
                        R.dimen.bubble_stack_starting_offset_y);
        // Since we're visually centering the bubbles on tablet, use total screen height rather
        // than the available height.
        final float height = isTablet
                ? mPositioner.getScreenRect().height()
                : mPositioner.getAvailableRect().height();
        float offsetPercent = desiredY / height;
        offsetPercent = Math.max(0f, Math.min(1f, offsetPercent));
        final RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        return allowableStackRegion.top + allowableStackRegion.height() * offsetPercent;
    }

    /**
     * Sets up window manager to return config values based on what you need for the test.
     * By default it sets up a portrait phone without any insets.
     */
    private class WindowManagerConfig {
        private Rect mScreenBounds = new Rect(0, 0, 1000, 2000);
        private boolean mIsLargeScreen = false;
        private int mOrientation = ORIENTATION_PORTRAIT;
        private int mLayoutDirection = LAYOUT_DIRECTION_LTR;
        private Insets mInsets = Insets.of(0, 0, 0, 0);

        public WindowManagerConfig setScreenBounds(Rect screenBounds) {
            mScreenBounds = screenBounds;
            return this;
        }

        public WindowManagerConfig setLargeScreen() {
            mIsLargeScreen = true;
            return this;
        }

        public WindowManagerConfig setOrientation(int orientation) {
            mOrientation = orientation;
            return this;
        }

        public WindowManagerConfig setLayoutDirection(int layoutDirection) {
            mLayoutDirection = layoutDirection;
            return this;
        }

        public WindowManagerConfig setInsets(Insets insets) {
            mInsets = insets;
            return this;
        }

        public void setUpConfig() {
            mConfiguration.smallestScreenWidthDp = mIsLargeScreen
                    ? MIN_WIDTH_FOR_TABLET
                    : MIN_WIDTH_FOR_TABLET - 1;
            mConfiguration.orientation = mOrientation;
            mConfiguration.screenWidthDp = pxToDp(mScreenBounds.width());
            mConfiguration.screenHeightDp = pxToDp(mScreenBounds.height());

            when(mConfiguration.getLayoutDirection()).thenReturn(mLayoutDirection);
            WindowInsets windowInsets = mock(WindowInsets.class);
            when(windowInsets.getInsetsIgnoringVisibility(anyInt())).thenReturn(mInsets);
            when(mWindowMetrics.getWindowInsets()).thenReturn(windowInsets);
            when(mWindowMetrics.getBounds()).thenReturn(mScreenBounds);
            when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        }

        private int pxToDp(float px) {
            int dpi = mContext.getResources().getDisplayMetrics().densityDpi;
            float dp = px / ((float) dpi / DisplayMetrics.DENSITY_DEFAULT);
            return (int) dp;
        }
    }
}
