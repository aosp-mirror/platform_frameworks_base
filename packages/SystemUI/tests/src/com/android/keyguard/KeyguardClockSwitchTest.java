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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Color;
import android.graphics.Paint.Style;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.text.TextPaint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;

import com.android.keyguard.clock.ClockManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
// Need to run on the main thread because KeyguardSliceView$Row init checks for
// the main thread before acquiring a wake lock. This class is constructed when
// the keyguard_clcok_switch layout is inflated.
@RunWithLooper(setAsMainLooper = true)
public class KeyguardClockSwitchTest extends SysuiTestCase {
    private FrameLayout mClockContainer;
    private StatusBarStateController.StateListener mStateListener;

    @Mock
    TextClock mClockView;
    @InjectMocks
    KeyguardClockSwitch mKeyguardClockSwitch;

    @Before
    public void setUp() {
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mKeyguardClockSwitch =
                (KeyguardClockSwitch) layoutInflater.inflate(R.layout.keyguard_clock_switch, null);
        mClockContainer = mKeyguardClockSwitch.findViewById(R.id.clock_view);
        MockitoAnnotations.initMocks(this);
        when(mClockView.getPaint()).thenReturn(mock(TextPaint.class));
        mStateListener = mKeyguardClockSwitch.getStateListener();
    }

    @Test
    public void onPluginConnected_showPluginClock() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);

        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);

        verify(mClockView).setVisibility(GONE);
        assertThat(plugin.getView().getParent()).isEqualTo(mClockContainer);
    }

    @Test
    public void onPluginConnected_showPluginBigClock() {
        // GIVEN that the container for the big clock has visibility GONE
        FrameLayout bigClockContainer = new FrameLayout(getContext());
        bigClockContainer.setVisibility(GONE);
        mKeyguardClockSwitch.setBigClockContainer(bigClockContainer);
        // AND the plugin returns a view for the big clock
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getBigClockView()).thenReturn(pluginView);
        // WHEN the plugin is connected
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);
        // THEN the big clock container is visible and it is the parent of the
        // big clock view.
        assertThat(bigClockContainer.getVisibility()).isEqualTo(VISIBLE);
        assertThat(pluginView.getParent()).isEqualTo(bigClockContainer);
    }

    @Test
    public void onPluginConnected_nullView() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);
        verify(mClockView, never()).setVisibility(GONE);
    }

    @Test
    public void onPluginConnected_showSecondPluginClock() {
        // GIVEN a plugin has already connected
        ClockPlugin plugin1 = mock(ClockPlugin.class);
        when(plugin1.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin1);
        // WHEN a second plugin is connected
        ClockPlugin plugin2 = mock(ClockPlugin.class);
        when(plugin2.getView()).thenReturn(new TextClock(getContext()));
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin2);
        // THEN only the view from the second plugin should be a child of KeyguardClockSwitch.
        assertThat(plugin2.getView().getParent()).isEqualTo(mClockContainer);
        assertThat(plugin1.getView().getParent()).isNull();
    }

    @Test
    public void onPluginConnected_darkAmountInitialized() {
        // GIVEN that the dark amount has already been set
        mKeyguardClockSwitch.setDarkAmount(0.5f);
        // WHEN a plugin is connected
        ClockPlugin plugin = mock(ClockPlugin.class);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);
        // THEN dark amount should be initalized on the plugin.
        verify(plugin).setDarkAmount(0.5f);
    }

    @Test
    public void onPluginDisconnected_showDefaultClock() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        mClockView.setVisibility(GONE);

        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(null);

        verify(mClockView).setVisibility(VISIBLE);
        assertThat(plugin.getView().getParent()).isNull();
    }

    @Test
    public void onPluginDisconnected_hidePluginBigClock() {
        // GIVEN that the big clock container is visible
        FrameLayout bigClockContainer = new FrameLayout(getContext());
        bigClockContainer.setVisibility(VISIBLE);
        mKeyguardClockSwitch.setBigClockContainer(bigClockContainer);
        // AND the plugin returns a view for the big clock
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getBigClockView()).thenReturn(pluginView);
        // WHEN the plugin is connected and then disconnected
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(null);
        // THEN the big lock container is GONE and the big clock view doesn't have
        // a parent.
        assertThat(bigClockContainer.getVisibility()).isEqualTo(GONE);
        assertThat(pluginView.getParent()).isNull();
    }

    @Test
    public void onPluginDisconnected_nullView() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(null);
        verify(mClockView, never()).setVisibility(GONE);
    }

    @Test
    public void onPluginDisconnected_secondOfTwoDisconnected() {
        // GIVEN two plugins are connected
        ClockPlugin plugin1 = mock(ClockPlugin.class);
        when(plugin1.getView()).thenReturn(new TextClock(getContext()));
        ClockManager.ClockChangedListener listener = mKeyguardClockSwitch.getClockChangedListener();
        listener.onClockChanged(plugin1);
        ClockPlugin plugin2 = mock(ClockPlugin.class);
        when(plugin2.getView()).thenReturn(new TextClock(getContext()));
        listener.onClockChanged(plugin2);
        // WHEN the second plugin is disconnected
        listener.onClockChanged(null);
        // THEN the default clock should be shown.
        verify(mClockView).setVisibility(VISIBLE);
        assertThat(plugin1.getView().getParent()).isNull();
        assertThat(plugin2.getView().getParent()).isNull();
    }

    @Test
    public void setTextColor_defaultClockSetTextColor() {
        mKeyguardClockSwitch.setTextColor(Color.YELLOW);

        verify(mClockView).setTextColor(Color.YELLOW);
    }

    @Test
    public void setTextColor_pluginClockSetTextColor() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);

        mKeyguardClockSwitch.setTextColor(Color.WHITE);

        verify(plugin).setTextColor(Color.WHITE);
    }

    @Test
    public void setStyle_defaultClockSetStyle() {
        TextPaint paint = mock(TextPaint.class);
        Style style = mock(Style.class);
        doReturn(paint).when(mClockView).getPaint();

        mKeyguardClockSwitch.setStyle(style);

        verify(paint).setStyle(style);
    }

    @Test
    public void setStyle_pluginClockSetStyle() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        Style style = mock(Style.class);
        mKeyguardClockSwitch.getClockChangedListener().onClockChanged(plugin);

        mKeyguardClockSwitch.setStyle(style);

        verify(plugin).setStyle(style);
    }

    @Test
    public void onStateChanged_InvisibleInShade() {
        // GIVEN that the big clock container is visible
        ViewGroup container = mock(ViewGroup.class);
        when(container.getVisibility()).thenReturn(View.VISIBLE);
        mKeyguardClockSwitch.setBigClockContainer(container);
        // WHEN transitioned to SHADE state
        mStateListener.onStateChanged(StatusBarState.SHADE);
        // THEN the container is invisible.
        verify(container).setVisibility(View.INVISIBLE);
    }

    @Test
    public void onStateChanged_VisibleInKeyguard() {
        // GIVEN that the big clock container is invisible
        ViewGroup container = mock(ViewGroup.class);
        when(container.getVisibility()).thenReturn(View.INVISIBLE);
        mKeyguardClockSwitch.setBigClockContainer(container);
        // WHEN transitioned to KEYGUARD state
        mStateListener.onStateChanged(StatusBarState.KEYGUARD);
        // THEN the container is visible.
        verify(container).setVisibility(View.VISIBLE);
    }
}
