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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Intent;
import android.graphics.Insets;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests operations and the resulting state managed by {@link BubblePositioner}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class BubblePositionerTest extends ShellTestCase {

    private BubblePositioner mPositioner;

    @Before
    public void setUp() {
        WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        mPositioner = new BubblePositioner(mContext, windowManager);
    }

    @Test
    public void testUpdate() {
        Insets insets = Insets.of(10, 20, 5, 15);
        Rect screenBounds = new Rect(0, 0, 1000, 1200);
        Rect availableRect = new Rect(screenBounds);
        availableRect.inset(insets);

        DeviceConfig deviceConfig = new ConfigBuilder()
                .setInsets(insets)
                .setScreenBounds(screenBounds)
                .build();
        mPositioner.update(deviceConfig);

        assertThat(mPositioner.getAvailableRect()).isEqualTo(availableRect);
        assertThat(mPositioner.isLandscape()).isFalse();
        assertThat(mPositioner.isLargeScreen()).isFalse();
        assertThat(mPositioner.getInsets()).isEqualTo(insets);
    }

    @Test
    public void testShowBubblesVertically_phonePortrait() {
        DeviceConfig deviceConfig = new ConfigBuilder().build();
        mPositioner.update(deviceConfig);

        assertThat(mPositioner.showBubblesVertically()).isFalse();
    }

    @Test
    public void testShowBubblesVertically_phoneLandscape() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLandscape().build();
        mPositioner.update(deviceConfig);

        assertThat(mPositioner.isLandscape()).isTrue();
        assertThat(mPositioner.showBubblesVertically()).isTrue();
    }

    @Test
    public void testShowBubblesVertically_tablet() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().build();
        mPositioner.update(deviceConfig);

        assertThat(mPositioner.showBubblesVertically()).isTrue();
    }

    /** If a resting position hasn't been set, calling it will return the default position. */
    @Test
    public void testGetRestingPosition_returnsDefaultPosition() {
        DeviceConfig deviceConfig = new ConfigBuilder().build();
        mPositioner.update(deviceConfig);

        PointF restingPosition = mPositioner.getRestingPosition();
        PointF defaultPosition = mPositioner.getDefaultStartPosition();

        assertThat(restingPosition).isEqualTo(defaultPosition);
    }

    /** If a resting position has been set, it'll return that instead of the default position. */
    @Test
    public void testGetRestingPosition_returnsRestingPosition() {
        DeviceConfig deviceConfig = new ConfigBuilder().build();
        mPositioner.update(deviceConfig);

        PointF restingPosition = new PointF(100, 100);
        mPositioner.setRestingPosition(restingPosition);

        assertThat(mPositioner.getRestingPosition()).isEqualTo(restingPosition);
    }

    /** Test that the default resting position on phone is in upper left. */
    @Test
    public void testGetRestingPosition_bubble_onPhone() {
        DeviceConfig deviceConfig = new ConfigBuilder().build();
        mPositioner.update(deviceConfig);

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.left);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testGetRestingPosition_bubble_onPhone_RTL() {
        DeviceConfig deviceConfig = new ConfigBuilder().setRtl().build();
        mPositioner.update(deviceConfig);

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.right);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    /** Test that the default resting position on tablet is middle left. */
    @Test
    public void testGetRestingPosition_chatBubble_onTablet() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().build();
        mPositioner.update(deviceConfig);

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.left);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testGetRestingPosition_chatBubble_onTablet_RTL() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().setRtl().build();
        mPositioner.update(deviceConfig);

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF restingPosition = mPositioner.getRestingPosition();

        assertThat(restingPosition.x).isEqualTo(allowableStackRegion.right);
        assertThat(restingPosition.y).isEqualTo(getDefaultYPosition());
    }

    /** Test that the default resting position on tablet is middle right. */
    @Test
    public void testGetDefaultPosition_appBubble_onTablet() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().build();
        mPositioner.update(deviceConfig);

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF startPosition = mPositioner.getDefaultStartPosition(true /* isAppBubble */);

        assertThat(startPosition.x).isEqualTo(allowableStackRegion.right);
        assertThat(startPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testGetRestingPosition_appBubble_onTablet_RTL() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().setRtl().build();
        mPositioner.update(deviceConfig);

        RectF allowableStackRegion =
                mPositioner.getAllowableStackPositionRegion(1 /* bubbleCount */);
        PointF startPosition = mPositioner.getDefaultStartPosition(true /* isAppBubble */);

        assertThat(startPosition.x).isEqualTo(allowableStackRegion.left);
        assertThat(startPosition.y).isEqualTo(getDefaultYPosition());
    }

    @Test
    public void testHasUserModifiedDefaultPosition_false() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().setRtl().build();
        mPositioner.update(deviceConfig);

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isFalse();

        mPositioner.setRestingPosition(mPositioner.getDefaultStartPosition());

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isFalse();
    }

    @Test
    public void testHasUserModifiedDefaultPosition_true() {
        DeviceConfig deviceConfig = new ConfigBuilder().setLargeScreen().setRtl().build();
        mPositioner.update(deviceConfig);

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isFalse();

        mPositioner.setRestingPosition(new PointF(0, 100));

        assertThat(mPositioner.hasUserModifiedDefaultPosition()).isTrue();
    }

    @Test
    public void testExpandedViewHeight_onLargeTablet() {
        Insets insets = Insets.of(10, 20, 5, 15);
        Rect screenBounds = new Rect(0, 0, 1800, 2600);

        DeviceConfig deviceConfig = new ConfigBuilder()
                .setLargeScreen()
                .setInsets(insets)
                .setScreenBounds(screenBounds)
                .build();
        mPositioner.update(deviceConfig);

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
    private static class ConfigBuilder {
        private Rect mScreenBounds = new Rect(0, 0, 1000, 2000);
        private boolean mIsLargeScreen = false;
        private boolean mIsSmallTablet = false;
        private boolean mIsLandscape = false;
        private boolean mIsRtl = false;
        private Insets mInsets = Insets.of(0, 0, 0, 0);

        public ConfigBuilder setScreenBounds(Rect screenBounds) {
            mScreenBounds = screenBounds;
            return this;
        }

        public ConfigBuilder setLargeScreen() {
            mIsLargeScreen = true;
            return this;
        }

        public ConfigBuilder setSmallTablet() {
            mIsSmallTablet = true;
            return this;
        }

        public ConfigBuilder setLandscape() {
            mIsLandscape = true;
            return this;
        }

        public ConfigBuilder setRtl() {
            mIsRtl = true;
            return this;
        }

        public ConfigBuilder setInsets(Insets insets) {
            mInsets = insets;
            return this;
        }

        private DeviceConfig build() {
            return new DeviceConfig(mIsLargeScreen, mIsSmallTablet, mIsLandscape, mIsRtl,
                    mScreenBounds, mInsets);
        }
    }
}
