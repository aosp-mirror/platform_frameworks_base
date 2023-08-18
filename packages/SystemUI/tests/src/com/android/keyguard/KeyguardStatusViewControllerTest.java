/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.plugins.ClockConfig;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner.class)
public class KeyguardStatusViewControllerTest extends KeyguardStatusViewControllerBaseTest {

    @Test
    public void dozeTimeTick_updatesSlice() {
        mController.dozeTimeTick();
        verify(mKeyguardSliceViewController).refresh();
    }

    @Test
    public void dozeTimeTick_updatesClock() {
        mController.dozeTimeTick();
        verify(mKeyguardClockSwitchController).refresh();
    }

    @Test
    public void setTranslationYExcludingMedia_forwardsCallToView() {
        float translationY = 123f;

        mController.setTranslationY(translationY, /* excludeMedia= */true);

        verify(mKeyguardStatusView).setChildrenTranslationY(translationY, /* excludeMedia= */true);
    }

    @Test
    public void onLocaleListChangedNotifiesClockSwitchController() {
        ArgumentCaptor<ConfigurationListener> configurationListenerArgumentCaptor =
                ArgumentCaptor.forClass(ConfigurationListener.class);

        mController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());

        configurationListenerArgumentCaptor.getValue().onLocaleListChanged();
        verify(mKeyguardClockSwitchController).onLocaleListChanged();
    }

    @Test
    public void updatePosition_primaryClockAnimation() {
        ClockController mockClock = mock(ClockController.class);
        when(mKeyguardClockSwitchController.getClock()).thenReturn(mockClock);
        when(mockClock.getConfig()).thenReturn(new ClockConfig("MOCK", false, true));

        mController.updatePosition(10, 15, 20f, true);

        verify(mControllerMock).setProperty(AnimatableProperty.Y, 15f, true);
        verify(mKeyguardClockSwitchController).updatePosition(
                10, 20f, KeyguardStatusViewController.CLOCK_ANIMATION_PROPERTIES, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_X, 1f, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_Y, 1f, true);
    }

    @Test
    public void updatePosition_alternateClockAnimation() {
        ClockController mockClock = mock(ClockController.class);
        when(mKeyguardClockSwitchController.getClock()).thenReturn(mockClock);
        when(mockClock.getConfig()).thenReturn(new ClockConfig("MOCK", true, true));

        mController.updatePosition(10, 15, 20f, true);

        verify(mControllerMock).setProperty(AnimatableProperty.Y, 15f, true);
        verify(mKeyguardClockSwitchController).updatePosition(
                10, 1f, KeyguardStatusViewController.CLOCK_ANIMATION_PROPERTIES, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_X, 20f, true);
        verify(mControllerMock).setProperty(AnimatableProperty.SCALE_Y, 20f, true);
    }

    @Test
    public void splitShadeEnabledPassedToClockSwitchController() {
        mController.setSplitShadeEnabled(true);
        verify(mKeyguardClockSwitchController, times(1)).setSplitShadeEnabled(true);
        verify(mKeyguardClockSwitchController, times(0)).setSplitShadeEnabled(false);
    }

    @Test
    public void splitShadeDisabledPassedToClockSwitchController() {
        mController.setSplitShadeEnabled(false);
        verify(mKeyguardClockSwitchController, times(1)).setSplitShadeEnabled(false);
        verify(mKeyguardClockSwitchController, times(0)).setSplitShadeEnabled(true);
    }

    @Test
    public void correctlyDump() {
        mController.onInit();
        verify(mDumpManager).registerDumpable(eq(mController.getInstanceName()), eq(mController));
        mController.onDestroy();
        verify(mDumpManager, times(1)).unregisterDumpable(eq(mController.getInstanceName()));
    }
}
