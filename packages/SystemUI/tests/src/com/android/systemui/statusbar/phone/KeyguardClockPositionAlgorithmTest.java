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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.util.BurnInHelperKt;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.FakeLogBuffer;
import com.android.systemui.res.R;
import com.android.systemui.shade.LargeScreenHeaderHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardClockPositionAlgorithmTest extends SysuiTestCase {
    private static final int SCREEN_HEIGHT = 2000;
    private static final int EMPTY_HEIGHT = 0;
    private static final float ZERO_DRAG = 0.f;
    private static final float OPAQUE = 1.f;
    private static final float TRANSPARENT = 0.f;

    @Mock private Resources mResources;

    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm;
    private KeyguardClockPositionAlgorithm.Result mClockPosition;

    private MockitoSession mStaticMockSession;

    private float mPanelExpansion;
    private int mKeyguardStatusBarHeaderHeight;
    private int mKeyguardStatusHeight;
    private int mUserSwitchHeight;
    private float mDark;
    private float mQsExpansion;
    private int mCutoutTopInset = 0;
    private boolean mIsSplitShade = false;
    private boolean mBypassEnabled = false;
    private int mUnlockedStackScrollerPadding = 0;
    private float mUdfpsTop = -1;
    private float mClockBottom = SCREEN_HEIGHT / 2;
    private boolean mClockTopAligned;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession = mockitoSession()
                .mockStatic(BurnInHelperKt.class)
                .mockStatic(LargeScreenHeaderHelper.class)
                .startMocking();

        LogBuffer logBuffer = FakeLogBuffer.Factory.Companion.create();
        mClockPositionAlgorithm = new KeyguardClockPositionAlgorithm(logBuffer);
        when(mResources.getDimensionPixelSize(anyInt())).thenReturn(0);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);

        mClockPosition = new KeyguardClockPositionAlgorithm.Result();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void clockPositionTopOfScreenOnAOD() {
        // GIVEN on AOD and clock has 0 height
        givenAOD();
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
        // GIVEN on AOD and clock has 0 height
        givenAOD();
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
        // GIVEN on lock screen with clock of 0 height
        givenLockScreen();
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock Y position is the top of the screen
        assertThat(mClockPosition.clockY).isEqualTo(0);
        // AND the clock is positioned on the left.
        assertThat(mClockPosition.clockX).isEqualTo(0);
    }

    @Test
    public void clockPositionWithPartialDragOnLockScreen() {
        // GIVEN dragging up on lock screen
        givenLockScreen();
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
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock is transparent
        assertThat(mClockPosition.clockAlpha).isEqualTo(TRANSPARENT);
    }

    @Test
    public void notifPositionTopOfScreenOnAOD() {
        // GIVEN on AOD and clock has 0 height
        givenAOD();
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
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is, unfortunately, the entire screen.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(SCREEN_HEIGHT);
    }

    @Test
    public void notifPositionMiddleOfScreenOnLockScreen() {
        // GIVEN on lock screen and clock has 0 height
        givenLockScreen();
        mKeyguardStatusHeight = EMPTY_HEIGHT;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif are placed to the top of the screen
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionAdjustsForClockHeightOnLockScreen() {
        // GIVEN on lock screen and stack scroller has a nonzero height
        givenLockScreen();
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(200);
    }

    @Test
    public void notifPositionAlignedWithClockInSplitShadeMode() {
        givenLockScreen();
        mIsSplitShade = true;
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding DOESN'T adjust for keyguard status height.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionAlignedWithClockAndBurnInOffsetInSplitShadeMode() {
        setSplitShadeTopMargin(100); // this makes clock to be at 100
        givenAOD();
        mIsSplitShade = true;
        givenMaxBurnInOffset(100);
        givenHighestBurnInOffset(); // this makes clock to be at 200
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding adjusts for burn-in offset: clock position - burn-in offset
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(100);
    }

    @Test
    public void clockPositionedDependingOnMarginInSplitShade() {
        setSplitShadeTopMargin(400);
        givenLockScreen();
        mIsSplitShade = true;
        // WHEN the position algorithm is run
        positionClock();

        assertThat(mClockPosition.clockY).isEqualTo(400);
    }

    @Test
    public void notifPaddingMakesUpToFullMarginInSplitShade_refactorFlagOff_usesResource() {
        mSetFlagsRule.disableFlags(Flags.FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR);
        int keyguardSplitShadeTopMargin = 100;
        int largeScreenHeaderHeightResource = 70;
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin))
                .thenReturn(keyguardSplitShadeTopMargin);
        when(mResources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height))
                .thenReturn(largeScreenHeaderHeightResource);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);
        givenLockScreen();
        mIsSplitShade = true;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding makes up lacking margin (margin - header height).
        int expectedPadding = keyguardSplitShadeTopMargin - largeScreenHeaderHeightResource;
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(expectedPadding);
    }

    @Test
    public void notifPaddingMakesUpToFullMarginInSplitShade_refactorFlagOn_usesHelper() {
        mSetFlagsRule.enableFlags(Flags.FLAG_CENTRALIZED_STATUS_BAR_DIMENS_REFACTOR);
        int keyguardSplitShadeTopMargin = 100;
        int largeScreenHeaderHeightHelper = 50;
        int largeScreenHeaderHeightResource = 70;
        when(LargeScreenHeaderHelper.getLargeScreenHeaderHeight(mContext))
                .thenReturn(largeScreenHeaderHeightHelper);
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin))
                .thenReturn(keyguardSplitShadeTopMargin);
        when(mResources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height))
                .thenReturn(largeScreenHeaderHeightResource);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);
        givenLockScreen();
        mIsSplitShade = true;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding makes up lacking margin (margin - header height).
        int expectedPadding = keyguardSplitShadeTopMargin - largeScreenHeaderHeightHelper;
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(expectedPadding);
    }

    @Test
    public void notifPaddingAccountsForMultiUserSwitcherInSplitShade() {
        setSplitShadeTopMargin(100);
        mUserSwitchHeight = 150;
        givenLockScreen();
        mIsSplitShade = true;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is split shade top margin + user switch height
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(250);
    }

    @Test
    public void clockDoesntAccountForMultiUserSwitcherInSplitShade() {
        setSplitShadeTopMargin(100);
        mUserSwitchHeight = 150;
        givenLockScreen();
        mIsSplitShade = true;
        // WHEN the position algorithm is run
        positionClock();
        // THEN clockY = split shade top margin
        assertThat(mClockPosition.clockY).isEqualTo(100);
    }

    @Test
    public void notifPaddingExpandedAlignedWithClockInSplitShadeMode() {
        givenLockScreen();
        mIsSplitShade = true;
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the padding DOESN'T adjust for keyguard status height.
        assertThat(mClockPosition.stackScrollerPaddingExpanded)
                .isEqualTo(mClockPosition.clockY);
    }

    @Test
    public void notifPadding_splitShade() {
        givenLockScreen();
        mIsSplitShade = true;
        mKeyguardStatusHeight = 200;
        // WHEN the position algorithm is run
        positionClock();
        // THEN the padding DOESN'T adjust for keyguard status height.
        assertThat(mClockPositionAlgorithm.getLockscreenNotifPadding(/* nsslTop= */ 10))
                .isEqualTo(mKeyguardStatusBarHeaderHeight - 10);
    }

    @Test
    public void notifPadding_portraitShade_bypassOff() {
        givenLockScreen();
        mIsSplitShade = false;
        mBypassEnabled = false;

        // mMinTopMargin = 100 = 80 + max(20, 0)
        mKeyguardStatusBarHeaderHeight = 80;
        mUserSwitchHeight = 20;
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin))
                .thenReturn(0);

        mKeyguardStatusHeight = 200;

        // WHEN the position algorithm is run
        positionClock();

        // THEN padding = 300 = mMinTopMargin(100) + mKeyguardStatusHeight(200)
        assertThat(mClockPositionAlgorithm.getLockscreenNotifPadding(/* nsslTop= */ 50))
                .isEqualTo(300);
    }

    @Test
    public void notifPadding_portraitShade_bypassOn() {
        givenLockScreen();
        mIsSplitShade = false;
        mBypassEnabled = true;
        mUnlockedStackScrollerPadding = 200;

        // WHEN the position algorithm is run
        positionClock();

        // THEN padding = 150 = mUnlockedStackScrollerPadding(200) - nsslTop(50)
        assertThat(mClockPositionAlgorithm.getLockscreenNotifPadding(/* nsslTop= */ 50))
                .isEqualTo(150);
    }

    @Test
    public void notifPositionWithLargeClockOnLockScreen() {
        // GIVEN on lock screen and clock has a nonzero height
        givenLockScreen();
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
        mKeyguardStatusHeight = SCREEN_HEIGHT;
        mPanelExpansion = 0.f;
        // WHEN the clock position algorithm is run
        positionClock();
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(
                (int) (mKeyguardStatusHeight * .667f));
    }

    @Test
    public void clockHiddenWhenQsIsExpanded() {
        // GIVEN on the lock screen with visible notifications
        givenLockScreen();
        mQsExpansion = 1;
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the clock is transparent.
        assertThat(mClockPosition.clockAlpha).isEqualTo(TRANSPARENT);
    }

    @Test
    public void clockNotHiddenWhenQsIsExpandedInSplitShade() {
        // GIVEN on the split lock screen with QS expansion
        givenLockScreen();
        mIsSplitShade = true;
        setSplitShadeTopMargin(100);
        mQsExpansion = 1;

        // WHEN the clock position algorithm is run
        positionClock();

        assertThat(mClockPosition.clockAlpha).isEqualTo(1);
    }

    @Test
    public void clockPositionMinimizesBurnInMovementToAvoidUdfpsOnAOD() {
        // GIVEN a center aligned clock
        mClockTopAligned = false;

        // GIVEN the clock + udfps are 100px apart
        mClockBottom = SCREEN_HEIGHT - 500;
        mUdfpsTop = SCREEN_HEIGHT - 400;

        // GIVEN it's AOD and the burn-in y value is 200
        givenAOD();
        givenMaxBurnInOffset(200);

        // WHEN the clock position algorithm is run with the highest burn in offset
        givenHighestBurnInOffset();
        positionClock();

        // THEN the worst-case clock Y position is shifted only by 100 (not the full 200),
        // so that it's at the same location as mUdfpsTop
        assertThat(mClockPosition.clockY).isEqualTo(100);

        // WHEN the clock position algorithm is run with the lowest burn in offset
        givenLowestBurnInOffset();
        positionClock();

        // THEN lowest case starts at 0
        assertThat(mClockPosition.clockY).isEqualTo(0);
    }

    @Test
    public void clockPositionShiftsToAvoidUdfpsOnAOD_usesSpaceAboveClock() {
        // GIVEN a center aligned clock
        mClockTopAligned = false;

        // GIVEN there's space at the top of the screen on LS (that's available to be used for
        // burn-in on AOD)
        mKeyguardStatusBarHeaderHeight = 150;

        // GIVEN the bottom of the clock is beyond the top of UDFPS
        mClockBottom = SCREEN_HEIGHT - 300;
        mUdfpsTop = SCREEN_HEIGHT - 400;

        // GIVEN it's AOD and the burn-in y value is 200
        givenAOD();
        givenMaxBurnInOffset(200);

        // WHEN the clock position algorithm is run with the highest burn in offset
        givenHighestBurnInOffset();
        positionClock();

        // THEN the algo should shift the clock up and use the area above the clock for
        // burn-in since the burn in offset > space above clock
        assertThat(mClockPosition.clockY).isEqualTo(mKeyguardStatusBarHeaderHeight);

        // WHEN the clock position algorithm is run with the lowest burn in offset
        givenLowestBurnInOffset();
        positionClock();

        // THEN lowest case starts at mCutoutTopInset (0 in this case)
        assertThat(mClockPosition.clockY).isEqualTo(mCutoutTopInset);
    }

    @Test
    public void clockPositionShiftsToAvoidUdfpsOnAOD_usesMaxBurnInOffset() {
        // GIVEN a center aligned clock
        mClockTopAligned = false;

        // GIVEN there's 200px space at the top of the screen on LS (that's available to be used for
        // burn-in on AOD) but 50px are taken up by the cutout
        mKeyguardStatusBarHeaderHeight = 200;
        mCutoutTopInset = 50;

        // GIVEN the bottom of the clock is beyond the top of UDFPS
        mClockBottom = SCREEN_HEIGHT - 300;
        mUdfpsTop = SCREEN_HEIGHT - 400;

        // GIVEN it's AOD and the burn-in y value is only 25px (less than space above clock)
        givenAOD();
        int maxYBurnInOffset = 25;
        givenMaxBurnInOffset(maxYBurnInOffset);

        // WHEN the clock position algorithm is run with the highest burn in offset
        givenHighestBurnInOffset();
        positionClock();

        // THEN the algo should shift the clock up and use the area above the clock for
        // burn-in
        assertThat(mClockPosition.clockY).isEqualTo(mKeyguardStatusBarHeaderHeight);

        // WHEN the clock position algorithm is run with the lowest burn in offset
        givenLowestBurnInOffset();
        positionClock();

        // THEN lowest case starts above mKeyguardStatusBarHeaderHeight
        assertThat(mClockPosition.clockY).isEqualTo(
                mKeyguardStatusBarHeaderHeight - 2 * maxYBurnInOffset);
    }

    @Test
    public void clockPositionShiftsToMaximizeUdfpsBurnInMovement() {
        // GIVEN a center aligned clock
        mClockTopAligned = false;

        // GIVEN there's 200px space at the top of the screen on LS (that's available to be used for
        // burn-in on AOD) but 50px are taken up by the cutout
        mKeyguardStatusBarHeaderHeight = 200;
        mCutoutTopInset = 50;
        int upperSpaceAvailable = mKeyguardStatusBarHeaderHeight - mCutoutTopInset;

        // GIVEN the bottom of the clock and the top of UDFPS are 100px apart
        mClockBottom = SCREEN_HEIGHT - 500;
        mUdfpsTop = SCREEN_HEIGHT - 400;
        float lowerSpaceAvailable = mUdfpsTop - mClockBottom;

        // GIVEN it's AOD and the burn-in y value is 200
        givenAOD();
        givenMaxBurnInOffset(200);

        // WHEN the clock position algorithm is run with the highest burn in offset
        givenHighestBurnInOffset();
        positionClock();

        // THEN the algo should shift the clock up and use both the area above
        // the clock and below the clock (vertically centered in its allowed area)
        assertThat(mClockPosition.clockY).isEqualTo(
                (int) (mCutoutTopInset + upperSpaceAvailable + lowerSpaceAvailable));

        // WHEN the clock position algorithm is run with the lowest burn in offset
        givenLowestBurnInOffset();
        positionClock();

        // THEN lowest case starts at mCutoutTopInset
        assertThat(mClockPosition.clockY).isEqualTo(mCutoutTopInset);
    }

    private void setSplitShadeTopMargin(int value) {
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin))
                .thenReturn(value);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);
    }

    private void givenHighestBurnInOffset() {
        when(BurnInHelperKt.getBurnInOffset(anyInt(), anyBoolean())).then(returnsFirstArg());
    }

    private void givenLowestBurnInOffset() {
        when(BurnInHelperKt.getBurnInOffset(anyInt(), anyBoolean())).thenReturn(0);
    }

    private void givenMaxBurnInOffset(int offset) {
        when(mResources.getDimensionPixelSize(R.dimen.burn_in_prevention_offset_y_clock))
                .thenReturn(offset);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);
    }

    private void givenAOD() {
        mPanelExpansion = 1.f;
        mDark = 1.f;
    }

    private void givenLockScreen() {
        mPanelExpansion = 1.f;
        mDark = 0.f;
    }

    /**
     * Setup and run the clock position algorithm.
     *
     * mClockPosition.clockY will contain the top y-coordinate for the clock position
     */
    private void positionClock() {
        mClockPositionAlgorithm.setup(
                mKeyguardStatusBarHeaderHeight,
                mPanelExpansion,
                mKeyguardStatusHeight,
                mUserSwitchHeight,
                0 /* userSwitchPreferredY */,
                mDark,
                ZERO_DRAG,
                mBypassEnabled,
                mUnlockedStackScrollerPadding,
                mQsExpansion,
                mCutoutTopInset,
                mIsSplitShade,
                mUdfpsTop,
                mClockBottom,
                mClockTopAligned);
        mClockPositionAlgorithm.run(mClockPosition);
    }
}
