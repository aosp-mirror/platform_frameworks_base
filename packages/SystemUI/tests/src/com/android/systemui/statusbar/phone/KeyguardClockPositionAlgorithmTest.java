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

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardClockPositionAlgorithmTest extends SysuiTestCase {

    private static final int SCREEN_HEIGHT = 2000;
    private static final int EMPTY_MARGIN = 0;
    private static final int EMPTY_HEIGHT = 0;
    private static final float ZERO_DRAG = 0.f;
    private static final float OPAQUE = 1.f;
    private static final float TRANSPARENT = 0.f;
    private static final boolean HAS_CUSTOM_CLOCK = false;
    private static final boolean HAS_VISIBLE_NOTIFS = false;

    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm;
    private KeyguardClockPositionAlgorithm.Result mClockPosition;
    private int mNotificationStackHeight;
    private float mPanelExpansion;
    private int mKeyguardStatusHeight;
    private float mDark;
    private boolean mHasCustomClock;
    private boolean mHasVisibleNotifs;
    private float mQsExpansion;
    private int mCutoutTopInset = 0; // in pixels
    private boolean mIsSplitShade = false;

    @Before
    public void setUp() {
        mClockPositionAlgorithm = new KeyguardClockPositionAlgorithm();
        mClockPosition = new KeyguardClockPositionAlgorithm.Result();

        mHasCustomClock = HAS_CUSTOM_CLOCK;
        mHasVisibleNotifs = HAS_VISIBLE_NOTIFS;
    }

    @Test
    public void clockPositionTopOfScreenOnAOD() {
        // GIVEN on AOD and both stack scroll and clock have 0 height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is the top of the screen
        assertThat(mClockPosition.clockY).isEqualTo(0);
        // AND the clock is opaque and positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isEqualTo(OPAQUE);
    }

    @Test
    public void clockPositionBelowCutout() {
        // GIVEN on AOD and both stack scroll and clock have 0 height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        mCutoutTopInset = 300;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is below the cutout
        assertThat(mClockPosition.clockY).isEqualTo(300);
        // AND the clock is opaque and positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isEqualTo(OPAQUE);
    }

    @Test
    public void clockPositionAdjustsForKeyguardStatusOnAOD() {
        // GIVEN on AOD with a clock of height 100
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = 100;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is at the top
        assertThat(mClockPosition.clockY).isEqualTo(0);
        // AND the clock is opaque and positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isEqualTo(OPAQUE);
    }

    @Test
    public void clockPositionLargeClockOnAOD() {
        // GIVEN on AOD with a full screen clock
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position doesn't overflow the screen.
        assertThat(mClockPosition.clockY).isEqualTo(0);
        // AND the clock is opaque and positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isEqualTo(OPAQUE);
    }

    @Test
    public void clockPositionTopOfScreenOnLockScreen() {
        // GIVEN on lock screen with stack scroll and clock of 0 height
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is the top of the screen
        assertThat(mClockPosition.clockY).isEqualTo(0);
        // AND the clock is positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
    }

    @Test
    public void clockPositionWithStackScrollExpandOnLockScreen() {
        // GIVEN on lock screen with stack scroll of height 500
        givenLockScreen();
        mNotificationStackHeight = 500;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position stays to the top
        assertThat(mClockPosition.clockY).isEqualTo(0);
        // AND the clock is positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
    }

    @Test
    public void clockPositionWithPartialDragOnLockScreen() {
        // GIVEN dragging up on lock screen
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        mPanelExpansion = 0.5f;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position adjusts with drag gesture.
        assertThat(mClockPosition.clockY).isLessThan(1000);
        // AND the clock is positioned on the left and not fully opaque.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isLessThan(OPAQUE);
    }

    @Test
    public void clockPositionWithFullDragOnLockScreen() {
        // GIVEN the lock screen is dragged up
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        mPanelExpansion = 0.f;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock is transparent.
        assertThat(mClockPosition.clockAlpha).isEqualTo(TRANSPARENT);
    }

    @Test
    public void largeClockOnLockScreenIsTransparent() {
        // GIVEN on lock screen with a full screen clock
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock is transparent
        assertThat(mClockPosition.clockAlpha).isEqualTo(TRANSPARENT);
    }

    @Test
    public void notifPositionTopOfScreenOnAOD() {
        // GIVEN on AOD and both stack scroll and clock have 0 height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is 0 (top of screen)
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionIndependentOfKeyguardStatusHeightOnAOD() {
        // GIVEN on AOD and clock has a nonzero height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = 100;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding adjusts for keyguard status height
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(100);
    }

    @Test
    public void notifPositionWithLargeClockOnAOD() {
        // GIVEN on AOD and clock has a nonzero height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is, unfortunately, the entire screen.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(SCREEN_HEIGHT);
    }

    @Test
    public void notifPositionMiddleOfScreenOnLockScreen() {
        // GIVEN on lock screen and both stack scroll and clock have 0 height
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif are placed to the top of the screen
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionAdjustsForStackHeightOnLockScreen() {
        // GIVEN on lock screen and stack scroller has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = 500;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding adjusts for keyguard status height
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionAdjustsForClockHeightOnLockScreen() {
        // GIVEN on lock screen and stack scroller has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding adjusts for both clock and notif stack.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(200);
    }

    @Test
    public void notifPositionAdjustsForStackHeightAndClockHeightOnLockScreen() {
        // GIVEN on lock screen and stack scroller has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = 500;
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notifs are placed below the statusview
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(200);
    }

    @Test
    public void notifPositionAlignedWithClockInSplitShadeMode() {
        // GIVEN on lock screen and split shade mode
        givenLockScreen();
        mIsSplitShade = true;
        mHasCustomClock = true;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding DOESN'T adjust for keyguard status height.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionWithLargeClockOnLockScreen() {
        // GIVEN on lock screen and clock has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is below keyguard status area
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(SCREEN_HEIGHT);
    }

    @Test
    public void notifPositionWithFullDragOnLockScreen() {
        // GIVEN the lock screen is dragged up
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        mPanelExpansion = 0.f;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the notif padding is zero.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionWithLargeClockFullDragOnLockScreen() {
        // GIVEN the lock screen is dragged up and a full screen clock
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        mPanelExpansion = 0.f;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the notif padding is zero.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void clockHiddenWhenQsIsExpanded() {
        // GIVEN on the lock screen with a custom clock and visible notifications
        givenLockScreen();
        mQsExpansion = 1;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is the middle of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.clockAlpha).isEqualTo(TRANSPARENT);
    }

    private void givenAOD() {
        mPanelExpansion = 1.f;
        mDark = 1.f;
    }

    private void givenLockScreen() {
        mPanelExpansion = 1.f;
        mDark = 0.f;
    }

    private void positionClock() {
        mClockPositionAlgorithm.setup(EMPTY_MARGIN, SCREEN_HEIGHT, mNotificationStackHeight,
                mPanelExpansion, SCREEN_HEIGHT, mKeyguardStatusHeight,
                0 /* userSwitchHeight */, 0 /* userSwitchPreferredY */,
                mHasCustomClock, mHasVisibleNotifs, mDark, ZERO_DRAG, false /* bypassEnabled */,
                0 /* unlockedStackScrollerPadding */, mQsExpansion,
                mCutoutTopInset, mIsSplitShade);
        mClockPositionAlgorithm.run(mClockPosition);
    }
}
