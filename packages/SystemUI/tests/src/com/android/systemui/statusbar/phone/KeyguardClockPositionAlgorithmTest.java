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

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardClockPositionAlgorithmTest extends SysuiTestCase {

    private static final int SCREEN_HEIGHT = 2000;
    private static final int EMPTY_MARGIN = 0;
    private static final int EMPTY_HEIGHT = 0;
    private static final boolean SECURE_LOCKED = false;
    private static final float ZERO_DRAG = 0.f;
    private static final float OPAQUE = 1.f;
    private static final float TRANSPARENT = 0.f;

    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm;
    private KeyguardClockPositionAlgorithm.Result mClockPosition;
    private int mNotificationStackHeight;
    private float mPanelExpansion;
    private int mKeyguardStatusHeight;
    private float mDark;

    @Before
    public void setUp() {
        mClockPositionAlgorithm = new KeyguardClockPositionAlgorithm();
        mClockPosition = new KeyguardClockPositionAlgorithm.Result();
    }

    @Test
    public void clockPositionMiddleOfScreenOnAOD() {
        // GIVEN on AOD and both stack scroll and clock have 0 height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is the middle of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.clockY).isEqualTo(1000);
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
        // THEN the clock Y position adjusts for the clock height (SCREEN_HEIGHT / 2 - 100).
        assertThat(mClockPosition.clockY).isEqualTo(900);
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
    public void clockPositionMiddleOfScreenOnLockScreen() {
        // GIVEN on lock screen with stack scroll and clock of 0 height
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is the middle of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.clockY).isEqualTo(1000);
        // AND the clock is opaque and positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isEqualTo(OPAQUE);
    }

    @Test
    public void clockPositionWithStackScrollExpandOnLockScreen() {
        // GIVEN on lock screen with stack scroll of height 500
        givenLockScreen();
        mNotificationStackHeight = 500;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position adjusts for stack scroll height ( (SCREEN_HEIGHT - 500 ) / 2).
        assertThat(mClockPosition.clockY).isEqualTo(750);
        // AND the clock is opaque and positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
        assertThat(mClockPosition.clockAlpha).isEqualTo(OPAQUE);
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
    public void notifPositionMiddleOfScreenOnAOD() {
        // GIVEN on AOD and both stack scroll and clock have 0 height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is half of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(1000);
    }

    @Test
    public void notifPositionIndependentOfKeyguardStatusHeightOnAOD() {
        // GIVEN on AOD and clock has a nonzero height
        givenAOD();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = 100;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is half of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(1000);
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
        // THEN the notif padding is half of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(1000);
    }

    @Test
    public void notifPositionAdjustsForStackHeightOnLockScreen() {
        // GIVEN on lock screen and stack scroller has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = 500;
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding adjusts for the expanded notif stack.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(750);
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
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(1000);
    }

    @Test
    public void notifPositionAdjustsForStackHeightAndClockHeightOnLockScreen() {
        // GIVEN on lock screen and stack scroller has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = 500;
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding adjusts for both clock and notif stack.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(810);
    }

    @Test
    public void notifPositionWithLargeClockOnLockScreen() {
        // GIVEN on lock screen and clock has a nonzero height
        givenLockScreen();
        mNotificationStackHeight = EMPTY_HEIGHT;
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is half of the screen (SCREEN_HEIGHT / 2).
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(1000);
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
                mPanelExpansion, SCREEN_HEIGHT, mKeyguardStatusHeight, mDark, SECURE_LOCKED,
                ZERO_DRAG);
        mClockPositionAlgorithm.run(mClockPosition);
    }
}
