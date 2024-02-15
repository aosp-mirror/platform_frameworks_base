/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.notification;

import static android.app.UiModeManager.MODE_ATTENTION_THEME_OVERLAY_NIGHT;
import static android.app.UiModeManager.MODE_ATTENTION_THEME_OVERLAY_OFF;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_APP;
import static android.service.notification.ZenModeConfig.UPDATE_ORIGIN_USER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.ColorDisplayManager;
import android.os.PowerManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(TestParameterInjector.class)
public class DefaultDeviceEffectsApplierTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private TestableContext mContext;
    private DefaultDeviceEffectsApplier mApplier;
    @Mock PowerManager mPowerManager;
    @Mock ColorDisplayManager mColorDisplayManager;
    @Mock UiModeManager mUiModeManager;
    @Mock WallpaperManager mWallpaperManager;

    private enum ChangeOrigin {
        ORIGIN_UNKNOWN(ZenModeConfig.UPDATE_ORIGIN_UNKNOWN),
        ORIGIN_INIT(ZenModeConfig.UPDATE_ORIGIN_INIT),
        ORIGIN_INIT_USER(ZenModeConfig.UPDATE_ORIGIN_INIT_USER),
        ORIGIN_USER(ZenModeConfig.UPDATE_ORIGIN_USER),
        ORIGIN_APP(ZenModeConfig.UPDATE_ORIGIN_APP),
        ORIGIN_SYSTEM_OR_SYSTEMUI(ZenModeConfig.UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI),
        ORIGIN_RESTORE_BACKUP(ZenModeConfig.UPDATE_ORIGIN_RESTORE_BACKUP);

        private final int mValue;

        ChangeOrigin(@ZenModeConfig.ConfigChangeOrigin int value) {
            mValue = value;
        }

        @ZenModeConfig.ConfigChangeOrigin
        public int value() {
            return mValue;
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(new TestableContext(InstrumentationRegistry.getContext(), null));
        mContext.addMockSystemService(PowerManager.class, mPowerManager);
        mContext.addMockSystemService(ColorDisplayManager.class, mColorDisplayManager);
        mContext.addMockSystemService(UiModeManager.class, mUiModeManager);
        mContext.addMockSystemService(WallpaperManager.class, mWallpaperManager);

        mApplier = new DefaultDeviceEffectsApplier(mContext);
    }

    @Test
    public void apply_appliesEffects() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(true)
                .build();
        mApplier.apply(effects, UPDATE_ORIGIN_USER);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(true));
        verify(mColorDisplayManager).setSaturationLevel(eq(0));
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.6f));
        verify(mUiModeManager).setAttentionModeThemeOverlay(eq(MODE_ATTENTION_THEME_OVERLAY_NIGHT));
    }

    @Test
    public void apply_removesEffects() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects previousEffects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(true)
                .build();
        mApplier.apply(previousEffects, UPDATE_ORIGIN_USER);
        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(true));
        verify(mColorDisplayManager).setSaturationLevel(eq(0));
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.6f));
        verify(mUiModeManager).setAttentionModeThemeOverlay(eq(MODE_ATTENTION_THEME_OVERLAY_NIGHT));

        ZenDeviceEffects noEffects = new ZenDeviceEffects.Builder().build();
        mApplier.apply(noEffects, UPDATE_ORIGIN_USER);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(false));
        verify(mColorDisplayManager).setSaturationLevel(eq(100));
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.0f));
        verify(mUiModeManager).setAttentionModeThemeOverlay(eq(MODE_ATTENTION_THEME_OVERLAY_OFF));
    }

    @Test
    public void apply_removesOnlyPreviouslyAppliedEffects() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects previousEffects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .build();
        mApplier.apply(previousEffects, UPDATE_ORIGIN_USER);
        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(true));

        ZenDeviceEffects noEffects = new ZenDeviceEffects.Builder().build();
        mApplier.apply(noEffects, UPDATE_ORIGIN_USER);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(false));
        verifyZeroInteractions(mColorDisplayManager, mWallpaperManager, mUiModeManager);
    }

    @Test
    public void apply_missingSomeServices_okay() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        mContext.addMockSystemService(ColorDisplayManager.class, null);
        mContext.addMockSystemService(WallpaperManager.class, null);
        mApplier = new DefaultDeviceEffectsApplier(mContext);

        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(true)
                .setShouldDisplayGrayscale(true)
                .setShouldUseNightMode(true)
                .build();
        mApplier.apply(effects, UPDATE_ORIGIN_USER);

        verify(mPowerManager).suppressAmbientDisplay(anyString(), eq(true));
        // (And no crash from missing services).
    }

    @Test
    public void apply_someEffects_onlyThoseEffectsApplied() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        ZenDeviceEffects effects = new ZenDeviceEffects.Builder()
                .setShouldDimWallpaper(true)
                .setShouldDisplayGrayscale(true)
                .build();
        mApplier.apply(effects, UPDATE_ORIGIN_USER);

        verify(mColorDisplayManager).setSaturationLevel(eq(0));
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.6f));

        verify(mPowerManager, never()).suppressAmbientDisplay(anyString(), anyBoolean());
        verify(mUiModeManager, never()).setAttentionModeThemeOverlay(anyInt());
    }

    @Test
    public void apply_onlyEffectDeltaApplied() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        mApplier.apply(new ZenDeviceEffects.Builder().setShouldDimWallpaper(true).build(),
                UPDATE_ORIGIN_USER);
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.6f));

        // Apply a second effect and remove the first one.
        mApplier.apply(new ZenDeviceEffects.Builder().setShouldDisplayGrayscale(true).build(),
                UPDATE_ORIGIN_USER);

        // Wallpaper dimming was undone, Grayscale was applied, nothing else was touched.
        verify(mWallpaperManager).setWallpaperDimAmount(eq(0.0f));
        verify(mColorDisplayManager).setSaturationLevel(eq(0));
        verifyZeroInteractions(mPowerManager);
        verifyZeroInteractions(mUiModeManager);
    }

    @Test
    public void apply_nightModeFromApp_appliedOnScreenOff() {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        when(mPowerManager.isInteractive()).thenReturn(true);

        mApplier.apply(new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build(),
                UPDATE_ORIGIN_APP);

        // Effect was not yet applied, but a broadcast receiver was registered.
        verifyZeroInteractions(mUiModeManager);
        verify(mContext).registerReceiver(broadcastReceiverCaptor.capture(),
                intentFilterCaptor.capture(), anyInt());
        assertThat(intentFilterCaptor.getValue().getAction(0)).isEqualTo(Intent.ACTION_SCREEN_OFF);
        BroadcastReceiver screenOffReceiver = broadcastReceiverCaptor.getValue();

        // Now the "screen off" event comes.
        screenOffReceiver.onReceive(mContext, new Intent(Intent.ACTION_SCREEN_OFF));

        // So the effect is applied, and we stopped listening for this event.
        verify(mUiModeManager).setAttentionModeThemeOverlay(eq(MODE_ATTENTION_THEME_OVERLAY_NIGHT));
        verify(mContext).unregisterReceiver(eq(screenOffReceiver));
    }

    @Test
    public void apply_nightModeWithScreenOff_appliedImmediately(
            @TestParameter ChangeOrigin origin) {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        when(mPowerManager.isInteractive()).thenReturn(false);

        mApplier.apply(new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build(),
                origin.value());

        // Effect was applied, and no broadcast receiver was registered.
        verify(mUiModeManager).setAttentionModeThemeOverlay(eq(MODE_ATTENTION_THEME_OVERLAY_NIGHT));
        verify(mContext, never()).registerReceiver(any(), any(), anyInt());
    }

    @Test
    @TestParameters({"{origin: ORIGIN_USER}", "{origin: ORIGIN_INIT}",
            "{origin: ORIGIN_INIT_USER}"})
    public void apply_nightModeWithScreenOn_appliedImmediatelyBasedOnOrigin(ChangeOrigin origin) {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        when(mPowerManager.isInteractive()).thenReturn(true);

        mApplier.apply(new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build(),
                origin.value());

        // Effect was applied, and no broadcast receiver was registered.
        verify(mUiModeManager).setAttentionModeThemeOverlay(eq(MODE_ATTENTION_THEME_OVERLAY_NIGHT));
        verify(mContext, never()).registerReceiver(any(), any(), anyInt());
    }

    @Test
    @TestParameters({"{origin: ORIGIN_APP}", "{origin: ORIGIN_RESTORE_BACKUP}",
            "{origin: ORIGIN_SYSTEM_OR_SYSTEMUI}", "{origin: ORIGIN_UNKNOWN}"})
    public void apply_nightModeWithScreenOn_willBeAppliedLaterBasedOnOrigin(ChangeOrigin origin) {
        mSetFlagsRule.enableFlags(android.app.Flags.FLAG_MODES_API);

        when(mPowerManager.isInteractive()).thenReturn(true);

        mApplier.apply(new ZenDeviceEffects.Builder().setShouldUseNightMode(true).build(),
                origin.value());

        // Effect was not applied, will be on next screen-off.
        verifyZeroInteractions(mUiModeManager);
        verify(mContext).registerReceiver(any(),
                argThat(filter -> Intent.ACTION_SCREEN_OFF.equals(filter.getAction(0))),
                anyInt());
    }
}
