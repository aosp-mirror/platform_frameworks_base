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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.keyguard.KeyguardClockSwitch.LARGE;
import static com.android.keyguard.KeyguardClockSwitch.SMALL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint.Style;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextClock;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.StatusBarState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
// Need to run on the main thread because KeyguardSliceView$Row init checks for
// the main thread before acquiring a wake lock. This class is constructed when
// the keyguard_clcok_switch layout is inflated.
@RunWithLooper(setAsMainLooper = true)
public class KeyguardClockSwitchTest extends SysuiTestCase {
    private FrameLayout mClockFrame;
    private FrameLayout mLargeClockFrame;
    private TextClock mBigClock;

    private AnimatableClockView mClockView;
    private AnimatableClockView mLargeClockView;
    View mMockKeyguardSliceView;
    KeyguardClockSwitch mKeyguardClockSwitch;

    @Before
    public void setUp() {
        mMockKeyguardSliceView = mock(KeyguardSliceView.class);
        when(mMockKeyguardSliceView.getContext()).thenReturn(mContext);
        when(mMockKeyguardSliceView.findViewById(R.id.keyguard_status_area))
                .thenReturn(mMockKeyguardSliceView);

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
        mClockFrame = mKeyguardClockSwitch.findViewById(R.id.lockscreen_clock_view);
        mClockView = mKeyguardClockSwitch.findViewById(R.id.animatable_clock_view);
        mLargeClockFrame = mKeyguardClockSwitch.findViewById(R.id.lockscreen_clock_view_large);
        mLargeClockView = mKeyguardClockSwitch.findViewById(R.id.animatable_clock_view_large);
        mBigClock = new TextClock(getContext());
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void onPluginConnected_showPluginClock() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);

        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);

        assertThat(mClockView.getVisibility()).isEqualTo(GONE);
        assertThat(plugin.getView().getParent()).isEqualTo(mClockFrame);
    }

    @Test
    public void onPluginConnected_showPluginBigClock() {
        // GIVEN the plugin returns a view for the big clock
        ClockPlugin plugin = mock(ClockPlugin.class);
        when(plugin.getBigClockView()).thenReturn(mBigClock);
        // WHEN the plugin is connected
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);
        // THEN the big clock container is visible and it is the parent of the
        // big clock view.
        assertThat(mLargeClockView.getVisibility()).isEqualTo(View.GONE);
        assertThat(mBigClock.getParent()).isEqualTo(mLargeClockFrame);
    }

    @Test
    public void onPluginConnected_nullView() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);
        assertThat(mClockView.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void onPluginConnected_showSecondPluginClock() {
        // GIVEN a plugin has already connected
        ClockPlugin plugin1 = mock(ClockPlugin.class);
        when(plugin1.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.setClockPlugin(plugin1, StatusBarState.KEYGUARD);
        // WHEN a second plugin is connected
        ClockPlugin plugin2 = mock(ClockPlugin.class);
        when(plugin2.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.setClockPlugin(plugin2, StatusBarState.KEYGUARD);
        // THEN only the view from the second plugin should be a child of KeyguardClockSwitch.
        assertThat(plugin2.getView().getParent()).isEqualTo(mClockFrame);
        assertThat(plugin1.getView().getParent()).isNull();
    }

    @Test
    public void onPluginConnected_darkAmountInitialized() {
        // GIVEN that the dark amount has already been set
        mKeyguardClockSwitch.setDarkAmount(0.5f);
        // WHEN a plugin is connected
        ClockPlugin plugin = mock(ClockPlugin.class);
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);
        // THEN dark amount should be initalized on the plugin.
        verify(plugin).setDarkAmount(0.5f);
    }

    @Test
    public void onPluginDisconnected_showDefaultClock() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);

        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);
        assertThat(mClockView.getVisibility()).isEqualTo(GONE);

        mKeyguardClockSwitch.setClockPlugin(null, StatusBarState.KEYGUARD);
        assertThat(mClockView.getVisibility()).isEqualTo(VISIBLE);

        assertThat(plugin.getView().getParent()).isNull();
    }

    @Test
    public void onPluginDisconnected_hidePluginBigClock() {
        // GIVEN the plugin returns a view for the big clock
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getBigClockView()).thenReturn(pluginView);
        // WHEN the plugin is connected and then disconnected
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);
        mKeyguardClockSwitch.setClockPlugin(null, StatusBarState.KEYGUARD);
        // THEN the big lock container is GONE and the big clock view doesn't have
        // a parent.
        assertThat(mLargeClockView.getVisibility()).isEqualTo(VISIBLE);
        assertThat(pluginView.getParent()).isNull();
    }

    @Test
    public void onPluginDisconnected_nullView() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);
        mKeyguardClockSwitch.setClockPlugin(null, StatusBarState.KEYGUARD);
        assertThat(mClockView.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void onPluginDisconnected_secondOfTwoDisconnected() {
        // GIVEN two plugins are connected
        ClockPlugin plugin1 = mock(ClockPlugin.class);
        when(plugin1.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.setClockPlugin(plugin1, StatusBarState.KEYGUARD);
        ClockPlugin plugin2 = mock(ClockPlugin.class);
        when(plugin2.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.setClockPlugin(plugin2, StatusBarState.KEYGUARD);
        // WHEN the second plugin is disconnected
        mKeyguardClockSwitch.setClockPlugin(null, StatusBarState.KEYGUARD);
        // THEN the default clock should be shown.
        assertThat(mClockView.getVisibility()).isEqualTo(VISIBLE);
        assertThat(plugin1.getView().getParent()).isNull();
        assertThat(plugin2.getView().getParent()).isNull();
    }

    @Test
    public void onPluginDisconnected_onDestroyView() {
        // GIVEN a plugin is connected
        ClockPlugin clockPlugin = mock(ClockPlugin.class);
        when(clockPlugin.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.setClockPlugin(clockPlugin, StatusBarState.KEYGUARD);
        // WHEN the plugin is disconnected
        mKeyguardClockSwitch.setClockPlugin(null, StatusBarState.KEYGUARD);
        // THEN onDestroyView is called on the plugin
        verify(clockPlugin).onDestroyView();
    }

    @Test
    public void setTextColor_pluginClockSetTextColor() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);

        mKeyguardClockSwitch.setTextColor(Color.WHITE);

        verify(plugin).setTextColor(Color.WHITE);
    }


    @Test
    public void setStyle_pluginClockSetStyle() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        Style style = mock(Style.class);
        mKeyguardClockSwitch.setClockPlugin(plugin, StatusBarState.KEYGUARD);

        mKeyguardClockSwitch.setStyle(style);

        verify(plugin).setStyle(style);
    }

    @Test
    public void switchingToBigClock_makesSmallClockDisappear() {
        mKeyguardClockSwitch.switchToClock(LARGE);

        mKeyguardClockSwitch.mClockInAnim.end();
        mKeyguardClockSwitch.mClockOutAnim.end();

        assertThat(mLargeClockFrame.getAlpha()).isEqualTo(1);
        assertThat(mLargeClockFrame.getVisibility()).isEqualTo(VISIBLE);
        assertThat(mClockFrame.getAlpha()).isEqualTo(0);
    }

    @Test
    public void switchingToSmallClock_makesBigClockDisappear() {
        mKeyguardClockSwitch.switchToClock(SMALL);

        mKeyguardClockSwitch.mClockInAnim.end();
        mKeyguardClockSwitch.mClockOutAnim.end();

        assertThat(mClockFrame.getAlpha()).isEqualTo(1);
        assertThat(mClockFrame.getVisibility()).isEqualTo(VISIBLE);
        // only big clock is removed at switch
        assertThat(mLargeClockFrame.getParent()).isNull();
        assertThat(mLargeClockFrame.getAlpha()).isEqualTo(0);
    }

    @Test
    public void switchingToBigClock_returnsTrueOnlyWhenItWasNotVisibleBefore() {
        assertThat(mKeyguardClockSwitch.switchToClock(LARGE)).isTrue();
        assertThat(mKeyguardClockSwitch.switchToClock(LARGE)).isFalse();
    }
}
