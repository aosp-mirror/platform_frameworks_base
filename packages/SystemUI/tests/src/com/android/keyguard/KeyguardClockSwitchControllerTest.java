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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.plugins.clocks.ClockFaceConfig;
import com.android.systemui.plugins.clocks.ClockTickRate;
import com.android.systemui.shared.clocks.ClockRegistry;
import com.android.systemui.statusbar.StatusBarState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.verification.VerificationMode;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeyguardClockSwitchControllerTest extends KeyguardClockSwitchControllerBaseTest {
    @Test
    public void testInit_viewAlreadyAttached() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        mController.init();

        verifyAttachment(times(1));
    }

    @Test
    public void testInit_viewNotYetAttached() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        ArgumentCaptor<View.OnAttachStateChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);

        when(mView.isAttachedToWindow()).thenReturn(false);
        mController.init();
        verify(mView).addOnAttachStateChangeListener(listenerArgumentCaptor.capture());

        verifyAttachment(never());

        listenerArgumentCaptor.getValue().onViewAttachedToWindow(mView);

        verifyAttachment(times(1));
    }

    @Test
    public void testInitSubControllers() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        mController.init();
        verify(mKeyguardSliceViewController).init();
    }

    @Test
    public void testInit_viewDetached() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        ArgumentCaptor<View.OnAttachStateChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        mController.init();
        verify(mView).addOnAttachStateChangeListener(listenerArgumentCaptor.capture());

        verifyAttachment(times(1));

        listenerArgumentCaptor.getValue().onViewDetachedFromWindow(mView);
        verify(mClockEventController).unregisterListeners();
    }

    @Test
    public void testPluginPassesStatusBarState() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        ArgumentCaptor<ClockRegistry.ClockChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ClockRegistry.ClockChangeListener.class);

        mController.init();
        verify(mClockRegistry).registerClockChangeListener(listenerArgumentCaptor.capture());

        listenerArgumentCaptor.getValue().onCurrentClockChanged();
        verify(mView, times(2)).setClock(mClockController, StatusBarState.SHADE);
        verify(mClockEventController, times(2)).setClock(mClockController);
    }

    @Test
    public void testSmartspaceEnabledRemovesKeyguardStatusArea() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mSmartspaceController.isEnabled()).thenReturn(true);
        mController.init();

        assertEquals(View.GONE, mSliceView.getVisibility());
    }

    @Test
    public void onLocaleListChangedRebuildsSmartspaceView() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mSmartspaceController.isEnabled()).thenReturn(true);
        mController.init();

        mController.onLocaleListChanged();
        // Should be called once on initial setup, then once again for locale change
        verify(mSmartspaceController, times(2)).buildAndConnectView(mView);
    }

    @Test
    public void onLocaleListChanged_rebuildsSmartspaceViews_whenDecouplingEnabled() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mSmartspaceController.isEnabled()).thenReturn(true);
        when(mSmartspaceController.isDateWeatherDecoupled()).thenReturn(true);
        mController.init();

        mController.onLocaleListChanged();
        // Should be called once on initial setup, then once again for locale change
        verify(mSmartspaceController, times(2)).buildAndConnectDateView(mView);
        verify(mSmartspaceController, times(2)).buildAndConnectWeatherView(mView);
        verify(mSmartspaceController, times(2)).buildAndConnectView(mView);
    }

    @Test
    public void testSmartspaceDisabledShowsKeyguardStatusArea() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mSmartspaceController.isEnabled()).thenReturn(false);
        mController.init();

        assertEquals(View.VISIBLE, mSliceView.getVisibility());
    }

    @Test
    public void testRefresh() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        mController.refresh();

        verify(mSmartspaceController).requestSmartspaceUpdate();
    }

    @Test
    public void testChangeToDoubleLineClockSetsSmallClock() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mSecureSettings.getIntForUser(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK, 1,
                UserHandle.USER_CURRENT))
                .thenReturn(0);
        ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);
        mController.init();
        mExecutor.runAllReady();
        verify(mSecureSettings).registerContentObserverForUserSync(
                eq(Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK),
                    anyBoolean(), observerCaptor.capture(), eq(UserHandle.USER_ALL));
        ContentObserver observer = observerCaptor.getValue();
        mExecutor.runAllReady();

        // When a settings change has occurred to the small clock, make sure the view is adjusted
        reset(mView);
        when(mView.getResources()).thenReturn(mResources);
        observer.onChange(true);
        mExecutor.runAllReady();
        verify(mView).switchToClock(KeyguardClockSwitch.SMALL, /* animate */ true);
    }

    @Test
    public void testGetClock_ForwardsToClock() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        assertEquals(mClockController, mController.getClock());
    }

    @Test
    public void testGetLargeClockBottom_returnsExpectedValue() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mLargeClockFrame.getVisibility()).thenReturn(View.VISIBLE);
        when(mLargeClockFrame.getHeight()).thenReturn(100);
        when(mSmallClockFrame.getHeight()).thenReturn(50);
        when(mLargeClockView.getHeight()).thenReturn(40);
        when(mSmallClockView.getHeight()).thenReturn(20);
        mController.init();

        assertEquals(170, mController.getClockBottom(1000));
    }

    @Test
    public void testGetSmallLargeClockBottom_returnsExpectedValue() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mLargeClockFrame.getVisibility()).thenReturn(View.GONE);
        when(mLargeClockFrame.getHeight()).thenReturn(100);
        when(mSmallClockFrame.getHeight()).thenReturn(50);
        when(mLargeClockView.getHeight()).thenReturn(40);
        when(mSmallClockView.getHeight()).thenReturn(20);
        mController.init();

        assertEquals(1120, mController.getClockBottom(1000));
    }

    @Test
    public void testGetClockBottom_nullClock_returnsZero() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mClockEventController.getClock()).thenReturn(null);
        assertEquals(0, mController.getClockBottom(10));
    }

    @Test
    public void testChangeLockscreenWeatherEnabledSetsWeatherViewVisible() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mSmartspaceController.isWeatherEnabled()).thenReturn(true);
        ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);
        mController.init();
        mExecutor.runAllReady();
        verify(mSecureSettings).registerContentObserverForUserSync(
                eq(Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED), anyBoolean(),
                    observerCaptor.capture(), eq(UserHandle.USER_ALL));
        ContentObserver observer = observerCaptor.getValue();
        mExecutor.runAllReady();
        // When a settings change has occurred, check that view is visible.
        observer.onChange(true);
        mExecutor.runAllReady();
        assertEquals(View.VISIBLE, mFakeWeatherView.getVisibility());
    }

    @Test
    public void testChangeClockDateWeatherEnabled_SetsDateWeatherViewVisibility() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        ArgumentCaptor<ClockRegistry.ClockChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(ClockRegistry.ClockChangeListener.class);
        when(mSmartspaceController.isEnabled()).thenReturn(true);
        when(mSmartspaceController.isDateWeatherDecoupled()).thenReturn(true);
        when(mSmartspaceController.isWeatherEnabled()).thenReturn(true);
        mController.init();
        mExecutor.runAllReady();
        assertEquals(View.VISIBLE, mFakeDateView.getVisibility());

        when(mSmallClockController.getConfig())
                .thenReturn(new ClockFaceConfig(ClockTickRate.PER_MINUTE, true, false, true));
        when(mLargeClockController.getConfig())
                .thenReturn(new ClockFaceConfig(ClockTickRate.PER_MINUTE, true, false, true));
        verify(mClockRegistry).registerClockChangeListener(listenerArgumentCaptor.capture());
        listenerArgumentCaptor.getValue().onCurrentClockChanged();

        mExecutor.runAllReady();
        assertEquals(View.INVISIBLE, mFakeDateView.getVisibility());
    }

    @Test
    public void testGetClock_nullClock_returnsNull() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        when(mClockEventController.getClock()).thenReturn(null);
        assertNull(mController.getClock());
    }

    private void verifyAttachment(VerificationMode times) {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        verify(mClockRegistry, times).registerClockChangeListener(
                any(ClockRegistry.ClockChangeListener.class));
        verify(mClockEventController, times).registerListeners(mView);
    }

    @Test
    public void testSplitShadeEnabledSetToSmartspaceController() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        mController.setSplitShadeEnabled(true);
        verify(mSmartspaceController, times(1)).setSplitShadeEnabled(true);
        verify(mSmartspaceController, times(0)).setSplitShadeEnabled(false);
    }

    @Test
    public void testSplitShadeDisabledSetToSmartspaceController() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);

        mController.setSplitShadeEnabled(false);
        verify(mSmartspaceController, times(1)).setSplitShadeEnabled(false);
        verify(mSmartspaceController, times(0)).setSplitShadeEnabled(true);
    }
}
