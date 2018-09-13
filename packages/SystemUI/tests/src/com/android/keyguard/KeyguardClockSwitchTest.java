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
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.graphics.Color;
import android.graphics.Paint.Style;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.text.TextPaint;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextClock;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.PluginManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner.class)
public class KeyguardClockSwitchTest extends SysuiTestCase {
    private PluginManager mPluginManager;

    @Mock
    TextClock mClockView;
    @InjectMocks
    KeyguardClockSwitch mKeyguardClockSwitch;

    @Before
    public void setUp() {
        mPluginManager = mDependency.injectMockDependency(PluginManager.class);
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mKeyguardClockSwitch =
                (KeyguardClockSwitch) layoutInflater.inflate(R.layout.keyguard_clock_switch, null);
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void onAttachToWindow_addPluginListener() {
        mKeyguardClockSwitch.onAttachedToWindow();

        ArgumentCaptor<PluginListener> listener = ArgumentCaptor.forClass(PluginListener.class);
        verify(mPluginManager).addPluginListener(listener.capture(), eq(ClockPlugin.class));
    }

    @Test
    public void onDetachToWindow_removePluginListener() {
        mKeyguardClockSwitch.onDetachedFromWindow();

        ArgumentCaptor<PluginListener> listener = ArgumentCaptor.forClass(PluginListener.class);
        verify(mPluginManager).removePluginListener(listener.capture());
    }

    @Test
    public void onPluginConnected_showPluginClock() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        TextPaint paint = mock(TextPaint.class);
        doReturn(paint).when(mClockView).getPaint();
        PluginListener listener = mKeyguardClockSwitch.getClockPluginListener();

        listener.onPluginConnected(plugin, null);

        verify(mClockView).setVisibility(GONE);
        assertThat(plugin.getView().getParent()).isEqualTo(mKeyguardClockSwitch);
    }

    @Test
    public void onPluginDisconnected_showDefaultClock() {
        ClockPlugin plugin = mock(ClockPlugin.class);
        TextClock pluginView = new TextClock(getContext());
        when(plugin.getView()).thenReturn(pluginView);
        mClockView.setVisibility(GONE);
        mKeyguardClockSwitch.addView(plugin.getView(), -1,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        PluginListener listener = mKeyguardClockSwitch.getClockPluginListener();

        listener.onPluginDisconnected(plugin);

        verify(mClockView).setVisibility(VISIBLE);
        assertThat(plugin.getView().getParent()).isNull();
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
        TextPaint paint = mock(TextPaint.class);
        doReturn(paint).when(mClockView).getPaint();
        PluginListener listener = mKeyguardClockSwitch.getClockPluginListener();
        listener.onPluginConnected(plugin, null);

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
        TextPaint paint = mock(TextPaint.class);
        doReturn(paint).when(mClockView).getPaint();
        Style style = mock(Style.class);
        PluginListener listener = mKeyguardClockSwitch.getClockPluginListener();
        listener.onPluginConnected(plugin, null);

        mKeyguardClockSwitch.setStyle(style);

        verify(plugin).setStyle(style);
    }
}
