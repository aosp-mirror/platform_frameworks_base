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

package com.android.systemui.theme;

import static com.android.systemui.keyguard.WakefulnessLifecycle.WAKEFULNESS_AWAKE;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.graphics.Color;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.util.settings.SecureSettings;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ThemeOverlayControllerTest extends SysuiTestCase {

    private ThemeOverlayController mThemeOverlayController;
    @Mock
    private Executor mBgExecutor;
    @Mock
    private Executor mMainExecutor;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private Handler mBgHandler;
    @Mock
    private ThemeOverlayApplier mThemeOverlayApplier;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private WallpaperManager mWallpaperManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private DeviceProvisionedController mDeviceProvisionedController;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiver;
    @Captor
    private ArgumentCaptor<WallpaperManager.OnColorsChangedListener> mColorsListener;
    @Captor
    private ArgumentCaptor<DeviceProvisionedListener> mDeviceProvisionedListener;
    @Captor
    private ArgumentCaptor<WakefulnessLifecycle.Observer> mWakefulnessLifecycleObserver;
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mFeatureFlags.isMonetEnabled()).thenReturn(true);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(WAKEFULNESS_AWAKE);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mThemeOverlayController = new ThemeOverlayController(null /* context */,
                mBroadcastDispatcher, mBgHandler, mMainExecutor, mBgExecutor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mDeviceProvisionedController,
                mUserTracker, mDumpManager, mFeatureFlags, mWakefulnessLifecycle) {
            @Nullable
            @Override
            protected FabricatedOverlay getOverlay(int color, int type) {
                FabricatedOverlay overlay = mock(FabricatedOverlay.class);
                when(overlay.getIdentifier())
                        .thenReturn(new OverlayIdentifier(Integer.toHexString(color | 0xff000000)));
                return overlay;
            }
        };

        mWakefulnessLifecycle.dispatchFinishedWakingUp();
        mThemeOverlayController.start();
        verify(mWallpaperManager).addOnColorsChangedListener(mColorsListener.capture(), eq(null),
                eq(UserHandle.USER_ALL));
        verify(mBroadcastDispatcher).registerReceiver(mBroadcastReceiver.capture(), any(),
                eq(mMainExecutor), any());
        verify(mWakefulnessLifecycle).addObserver(mWakefulnessLifecycleObserver.capture());
        verify(mDumpManager).registerDumpable(any(), any());
        verify(mDeviceProvisionedController).addCallback(mDeviceProvisionedListener.capture());
    }

    @Test
    public void start_checksWallpaper() {
        ArgumentCaptor<Runnable> registrationRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mBgExecutor).execute(registrationRunnable.capture());

        registrationRunnable.getValue().run();
        verify(mWallpaperManager).getWallpaperColors(eq(WallpaperManager.FLAG_SYSTEM));
    }

    @Test
    public void onWallpaperColorsChanged_setsTheme() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(new OverlayIdentifier("ffff0000"));
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_ACCENT_COLOR))
                .isEqualTo(new OverlayIdentifier("ffff0000"));

        // Should not ask again if changed to same value
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        verifyNoMoreInteractions(mThemeOverlayApplier);

        // Should not ask again even for new colors until we change wallpapers
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.BLACK),
                null, null), WallpaperManager.FLAG_SYSTEM);
        verifyNoMoreInteractions(mThemeOverlayApplier);

        // But should change theme after changing wallpapers
        clearInvocations(mThemeOverlayApplier);
        mBroadcastReceiver.getValue().onReceive(null, new Intent(Intent.ACTION_WALLPAPER_CHANGED));
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.BLACK),
                null, null), WallpaperManager.FLAG_SYSTEM);
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_preservesWallpaperPickerTheme() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"preset\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(new OverlayIdentifier("override.package.name"));
    }

    @Test
    public void onWallpaperColorsChanged_resetThemeIfNotPreset() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());

        assertThat(updatedSetting.getValue().contains("android.theme.customization.accent_color"))
                .isFalse();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.system_palette"))
                .isFalse();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.color_index"))
                .isFalse();

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_ResetThemeWithDifferentWallpapers() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)).thenReturn(20);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)).thenReturn(21);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());

        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_both\":\"0")).isTrue();

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_ResetThemeWithSameWallpaper() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)).thenReturn(-1);

        mColorsListener.getValue().onColorsChanged(mainColors,
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());

        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_both\":\"1")).isTrue();

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_changeLockWallpaper() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)).thenReturn(1);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_LOCK);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());
        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_source\":\"lock_wallpaper")).isTrue();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.color_index"))
                .isFalse();
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_changeHomeWallpaper() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"lock_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)).thenReturn(-1);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());
        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_source\":\"home_wallpaper")).isTrue();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.color_index"))
                .isFalse();
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_ResetThemeWhenFromLatestWallpaper() {
        // Should ask for a new theme when the colors of the last applied wallpaper change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)).thenReturn(1);
        // SYSTEM wallpaper is the last applied one
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)).thenReturn(2);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_keepThemeIfNotLatestWallpaper() {
        // Shouldn't ask for a new theme when the colors of the wallpaper that is not the last
        // applied one change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"override.package.name\","
                        + "\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)).thenReturn(1);
        // SYSTEM wallpaper is the last applied one
        when(mWallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)).thenReturn(2);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_LOCK);

        verify(mSecureSettings, never()).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), any());


        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onProfileAdded_setsTheme() {
        mBroadcastReceiver.getValue().onReceive(null,
                new Intent(Intent.ACTION_MANAGED_PROFILE_ADDED));
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onUserAdded_appliesTheme_ifNotManagedProfile() {
        reset(mDeviceProvisionedController);
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(false);
        mBroadcastReceiver.getValue().onReceive(null,
                new Intent(Intent.ACTION_MANAGED_PROFILE_ADDED));
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onProfileAdded_ignoresUntilSetupComplete() {
        reset(mDeviceProvisionedController);
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);
        mBroadcastReceiver.getValue().onReceive(null,
                new Intent(Intent.ACTION_MANAGED_PROFILE_ADDED));
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_firstEventBeforeUserSetup_shouldBeAccepted() {
        // By default, on setup() we make this controller return that the user finished setup
        // wizard. This test on the other hand, is testing the setup flow.
        reset(mDeviceProvisionedController);
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());

        // Regression test: null events should not reset the internal state and allow colors to be
        // applied again.
        clearInvocations(mThemeOverlayApplier);
        mBroadcastReceiver.getValue().onReceive(null, new Intent(Intent.ACTION_WALLPAPER_CHANGED));
        mColorsListener.getValue().onColorsChanged(null, WallpaperManager.FLAG_SYSTEM);
        verify(mThemeOverlayApplier, never()).applyCurrentUserOverlays(any(), any(), anyInt(),
                any(), any());
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.GREEN),
                null, null), WallpaperManager.FLAG_SYSTEM);
        verify(mThemeOverlayApplier, never()).applyCurrentUserOverlays(any(), any(), anyInt(),
                any(), any());
    }

    @Test
    public void catchException_whenPackageNameIsOverlayName() {
        mDeviceProvisionedController = mock(DeviceProvisionedController.class);
        mThemeOverlayApplier = mock(ThemeOverlayApplier.class);
        mWallpaperManager = mock(WallpaperManager.class);

        // Assume we have some wallpaper colors at boot.
        when(mWallpaperManager.getWallpaperColors(anyInt()))
                .thenReturn(new WallpaperColors(Color.valueOf(Color.GRAY), null, null));

        Executor executor = MoreExecutors.directExecutor();

        mThemeOverlayController = new ThemeOverlayController(null /* context */,
                mBroadcastDispatcher, mBgHandler, executor, executor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mDeviceProvisionedController,
                mUserTracker, mDumpManager, mFeatureFlags, mWakefulnessLifecycle) {
            @Nullable
            @Override
            protected FabricatedOverlay getOverlay(int color, int type) {
                FabricatedOverlay overlay = mock(FabricatedOverlay.class);
                when(overlay.getIdentifier())
                        .thenReturn(new OverlayIdentifier("com.thebest.livewallpaperapp.ever"));

                return overlay;
            }

        };
        mThemeOverlayController.start();

        verify(mWallpaperManager).addOnColorsChangedListener(mColorsListener.capture(), eq(null),
                eq(UserHandle.USER_ALL));
        verify(mDeviceProvisionedController).addCallback(mDeviceProvisionedListener.capture());

        // Colors were applied during controller initialization.
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
        clearInvocations(mThemeOverlayApplier);
    }

    @Test
    public void onWallpaperColorsChanged_defersUntilSetupIsCompleted_ifHasColors() {
        mDeviceProvisionedController = mock(DeviceProvisionedController.class);
        mThemeOverlayApplier = mock(ThemeOverlayApplier.class);
        mWallpaperManager = mock(WallpaperManager.class);

        // Assume we have some wallpaper colors at boot.
        when(mWallpaperManager.getWallpaperColors(anyInt()))
                .thenReturn(new WallpaperColors(Color.valueOf(Color.GRAY), null, null));

        Executor executor = MoreExecutors.directExecutor();
        mThemeOverlayController = new ThemeOverlayController(null /* context */,
                mBroadcastDispatcher, mBgHandler, executor, executor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mDeviceProvisionedController,
                mUserTracker, mDumpManager, mFeatureFlags, mWakefulnessLifecycle) {
            @Nullable
            @Override
            protected FabricatedOverlay getOverlay(int color, int type) {
                FabricatedOverlay overlay = mock(FabricatedOverlay.class);
                when(overlay.getIdentifier())
                        .thenReturn(new OverlayIdentifier(Integer.toHexString(color | 0xff000000)));
                return overlay;
            }
        };
        mThemeOverlayController.start();
        verify(mWallpaperManager).addOnColorsChangedListener(mColorsListener.capture(), eq(null),
                eq(UserHandle.USER_ALL));
        verify(mDeviceProvisionedController).addCallback(mDeviceProvisionedListener.capture());

        // Colors were applied during controller initialization.
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
        clearInvocations(mThemeOverlayApplier);

        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        // Defers event because we already have initial colors.
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());

        // Then event happens after setup phase is over.
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mDeviceProvisionedListener.getValue().onUserSetupChanged();
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_screenOff_deviceSetupNotFinished_doesNotProcessQueued() {
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);
        mDeviceProvisionedListener.getValue().onUserSetupChanged();


        // Second color application is not applied.
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        clearInvocations(mThemeOverlayApplier);

        // Device went to sleep and second set of colors was applied.
        mainColors =  new WallpaperColors(Color.valueOf(Color.BLUE),
                Color.valueOf(Color.RED), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());

        mWakefulnessLifecycle.dispatchFinishedGoingToSleep();
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_screenOff_processesQueued() {
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mDeviceProvisionedListener.getValue().onUserSetupChanged();

        // Second color application is not applied.
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);

        clearInvocations(mThemeOverlayApplier);

        // Device went to sleep and second set of colors was applied.
        mainColors =  new WallpaperColors(Color.valueOf(Color.BLUE),
                Color.valueOf(Color.RED), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());

        mWakefulnessLifecycleObserver.getValue().onFinishedGoingToSleep();
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void onWallpaperColorsChanged_parsesColorsFromWallpaperPicker() {
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.system_palette\":\"00FF00\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(new OverlayIdentifier("ff00ff00"));
    }
}
