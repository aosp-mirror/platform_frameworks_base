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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

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
import org.mockito.quality.Strictness;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardClockPositionAlgorithmTest extends SysuiTestCase {
    private static final float ZERO_DRAG = 0.f;

    @Mock private Resources mResources;

    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm;
    private KeyguardClockPositionAlgorithm.Result mClockPosition;

    private MockitoSession mStaticMockSession;

    private float mDark;
    private boolean mIsSplitShade = false;
    private boolean mBypassEnabled = false;
    private int mUnlockedStackScrollerPadding = 0;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession = mockitoSession()
                .strictness(Strictness.WARN)
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
    public void notifPositionTopOfScreenOnAOD() {
        // GIVEN on AOD and clock has 0 height
        givenAOD();
        // WHEN the position algorithm is run
        positionClock();
        // THEN the notif padding is 0 (top of screen)
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    @Test
    public void notifPositionAlignedWithClockInSplitShadeMode() {
        givenLockScreen();
        mIsSplitShade = true;
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
    public void notifPaddingMakesUpToFullMarginInSplitShade_usesHelper() {
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
    public void notifPositionWithFullDragOnLockScreen() {
        // GIVEN the lock screen is dragged up
        givenLockScreen();
        // WHEN the clock position algorithm is run
        positionClock();
        // THEN the notif padding is zero.
        assertThat(mClockPosition.stackScrollerPadding).isEqualTo(0);
    }

    private void setSplitShadeTopMargin(int value) {
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin))
                .thenReturn(value);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);
    }

    private void givenHighestBurnInOffset() {
        when(BurnInHelperKt.getBurnInOffset(anyInt(), anyBoolean())).then(returnsFirstArg());
    }

    private void givenMaxBurnInOffset(int offset) {
        when(mResources.getDimensionPixelSize(R.dimen.burn_in_prevention_offset_y_clock))
                .thenReturn(offset);
        mClockPositionAlgorithm.loadDimens(mContext, mResources);
    }

    private void givenAOD() {
        mDark = 1.f;
    }

    private void givenLockScreen() {
        mDark = 0.f;
    }

    /**
     * Setup and run the clock position algorithm.
     *
     * mClockPosition.clockY will contain the top y-coordinate for the clock position
     */
    private void positionClock() {
        mClockPositionAlgorithm.setup(
                mDark,
                ZERO_DRAG,
                mBypassEnabled,
                mUnlockedStackScrollerPadding,
                mIsSplitShade);
        mClockPositionAlgorithm.run(mClockPosition);
    }
}
