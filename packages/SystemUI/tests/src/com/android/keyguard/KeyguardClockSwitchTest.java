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
 * limitations under the License
 */

package com.android.keyguard;

import static android.view.View.VISIBLE;

import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertEquals;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.plugins.ClockFaceController;
import com.android.systemui.statusbar.StatusBarState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
// Need to run on the main thread because KeyguardSliceView$Row init checks for
// the main thread before acquiring a wake lock. This class is constructed when
// the keyguard_clock_switch layout is inflated.
@RunWithLooper(setAsMainLooper = true)
public class KeyguardClockSwitchTest extends SysuiTestCase {
    @Mock
    ViewGroup mMockKeyguardSliceView;

    @Mock
    ClockController mClock;

    @Mock
    ClockFaceController mSmallClock;

    @Mock
    ClockFaceController mLargeClock;

    private FrameLayout mSmallClockFrame;
    private FrameLayout mLargeClockFrame;

    KeyguardClockSwitch mKeyguardClockSwitch;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockKeyguardSliceView.getContext()).thenReturn(mContext);
        when(mMockKeyguardSliceView.findViewById(R.id.keyguard_status_area))
                .thenReturn(mMockKeyguardSliceView);

        when(mClock.getSmallClock()).thenReturn(mSmallClock);
        when(mClock.getLargeClock()).thenReturn(mLargeClock);

        when(mSmallClock.getView()).thenReturn(new TextView(getContext()));
        when(mLargeClock.getView()).thenReturn(new TextView(getContext()));

        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        layoutInflater.setPrivateFactory(new LayoutInflater.Factory2() {

            @Override
            public View onCreateView(View parent, String name, Context context,
                    AttributeSet attrs) {
                return onCreateView(name, context, attrs);
            }

            @Override
            public View onCreateView(String name, Context context, AttributeSet attrs) {
                if ("com.android.keyguard.KeyguardSliceView".equals(name)) {
                    return mMockKeyguardSliceView;
                }
                return null;
            }
        });
        mKeyguardClockSwitch =
                (KeyguardClockSwitch) layoutInflater.inflate(R.layout.keyguard_clock_switch, null);
        mSmallClockFrame = mKeyguardClockSwitch.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mKeyguardClockSwitch.findViewById(R.id.lockscreen_clock_view_large);
        mKeyguardClockSwitch.mChildrenAreLaidOut = true;
    }

    @Test
    public void noPluginConnected_showNothing() {
        mKeyguardClockSwitch.setClock(null, StatusBarState.KEYGUARD);
        assertEquals(mLargeClockFrame.getChildCount(), 0);
        assertEquals(mSmallClockFrame.getChildCount(), 0);
    }

    @Test
    public void pluginConnectedThenDisconnected_showNothing() {
        mKeyguardClockSwitch.setClock(mClock, StatusBarState.KEYGUARD);
        assertEquals(mLargeClockFrame.getChildCount(), 1);
        assertEquals(mSmallClockFrame.getChildCount(), 1);

        mKeyguardClockSwitch.setClock(null, StatusBarState.KEYGUARD);
        assertEquals(mLargeClockFrame.getChildCount(), 0);
        assertEquals(mSmallClockFrame.getChildCount(), 0);
    }

    @Test
    public void onPluginConnected_showClock() {
        mKeyguardClockSwitch.setClock(mClock, StatusBarState.KEYGUARD);

        assertEquals(mClock.getSmallClock().getView().getParent(), mSmallClockFrame);
        assertEquals(mClock.getLargeClock().getView().getParent(), mLargeClockFrame);
    }

    @Test
    public void onPluginConnected_showSecondPluginClock() {
        // GIVEN a plugin has already connected
        ClockController otherClock = mock(ClockController.class);
        ClockFaceController smallClock = mock(ClockFaceController.class);
        ClockFaceController largeClock = mock(ClockFaceController.class);
        when(otherClock.getSmallClock()).thenReturn(smallClock);
        when(otherClock.getLargeClock()).thenReturn(largeClock);
        when(smallClock.getView()).thenReturn(new TextView(getContext()));
        when(largeClock.getView()).thenReturn(new TextView(getContext()));
        mKeyguardClockSwitch.setClock(mClock, StatusBarState.KEYGUARD);
        mKeyguardClockSwitch.setClock(otherClock, StatusBarState.KEYGUARD);

        // THEN only the view from the second plugin should be a child of KeyguardClockSwitch.
        assertThat(otherClock.getSmallClock().getView().getParent()).isEqualTo(mSmallClockFrame);
        assertThat(otherClock.getLargeClock().getView().getParent()).isEqualTo(mLargeClockFrame);
        assertThat(mClock.getSmallClock().getView().getParent()).isNull();
        assertThat(mClock.getLargeClock().getView().getParent()).isNull();
    }

    @Test
    public void onPluginDisconnected_secondOfTwoDisconnected() {
        // GIVEN two plugins are connected
        ClockController otherClock = mock(ClockController.class);
        ClockFaceController smallClock = mock(ClockFaceController.class);
        ClockFaceController largeClock = mock(ClockFaceController.class);
        when(otherClock.getSmallClock()).thenReturn(smallClock);
        when(otherClock.getLargeClock()).thenReturn(largeClock);
        when(smallClock.getView()).thenReturn(new TextView(getContext()));
        when(largeClock.getView()).thenReturn(new TextView(getContext()));
        mKeyguardClockSwitch.setClock(otherClock, StatusBarState.KEYGUARD);
        mKeyguardClockSwitch.setClock(mClock, StatusBarState.KEYGUARD);
        // WHEN the second plugin is disconnected
        mKeyguardClockSwitch.setClock(null, StatusBarState.KEYGUARD);
        // THEN nothing should be shown
        assertThat(otherClock.getSmallClock().getView().getParent()).isNull();
        assertThat(otherClock.getLargeClock().getView().getParent()).isNull();
        assertThat(mClock.getSmallClock().getView().getParent()).isNull();
        assertThat(mClock.getLargeClock().getView().getParent()).isNull();
    }

    @Test
    public void switchingToBigClockWithAnimation_makesSmallClockDisappear() {
        mKeyguardClockSwitch.switchToClock(LARGE, /* animate */ true);

        mKeyguardClockSwitch.mClockInAnim.end();
        mKeyguardClockSwitch.mClockOutAnim.end();

        assertThat(mLargeClockFrame.getAlpha()).isEqualTo(1);
        assertThat(mLargeClockFrame.getVisibility()).isEqualTo(VISIBLE);
        assertThat(mSmallClockFrame.getAlpha()).isEqualTo(0);
    }

    @Test
    public void switchingToBigClockNoAnimation_makesSmallClockDisappear() {
        mKeyguardClockSwitch.switchToClock(LARGE, /* animate */ false);

        assertThat(mLargeClockFrame.getAlpha()).isEqualTo(1);
        assertThat(mLargeClockFrame.getVisibility()).isEqualTo(VISIBLE);
        assertThat(mSmallClockFrame.getAlpha()).isEqualTo(0);
    }

    @Test
    public void switchingToSmallClockWithAnimation_makesBigClockDisappear() {
        mKeyguardClockSwitch.switchToClock(SMALL, /* animate */ true);

        mKeyguardClockSwitch.mClockInAnim.end();
        mKeyguardClockSwitch.mClockOutAnim.end();

        assertThat(mSmallClockFrame.getAlpha()).isEqualTo(1);
        assertThat(mSmallClockFrame.getVisibility()).isEqualTo(VISIBLE);
        // only big clock is removed at switch
        assertThat(mLargeClockFrame.getParent()).isNull();
        assertThat(mLargeClockFrame.getAlpha()).isEqualTo(0);
    }

    @Test
    public void switchingToSmallClockNoAnimation_makesBigClockDisappear() {
        mKeyguardClockSwitch.switchToClock(SMALL, false);

        assertThat(mSmallClockFrame.getAlpha()).isEqualTo(1);
        assertThat(mSmallClockFrame.getVisibility()).isEqualTo(VISIBLE);
        // only big clock is removed at switch
        assertThat(mLargeClockFrame.getParent()).isNull();
        assertThat(mLargeClockFrame.getAlpha()).isEqualTo(0);
    }

    @Test
    public void switchingToBigClock_returnsTrueOnlyWhenItWasNotVisibleBefore() {
        assertThat(mKeyguardClockSwitch.switchToClock(LARGE, /* animate */ true)).isTrue();
        assertThat(mKeyguardClockSwitch.switchToClock(LARGE, /* animate */ true)).isFalse();
    }
}
