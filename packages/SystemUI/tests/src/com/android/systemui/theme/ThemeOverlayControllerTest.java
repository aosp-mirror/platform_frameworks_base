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

import static android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE;
import static android.util.TypedValue.TYPE_INT_COLOR_ARGB8;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.UiModeManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.annotation.VisibleForTesting;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.monet.Style;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.settings.SecureSettings;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ThemeOverlayControllerTest extends SysuiTestCase {

    private static final int USER_SYSTEM = UserHandle.USER_SYSTEM;
    private static final int USER_SECONDARY = 10;
    private static final UserHandle MANAGED_USER_HANDLE = UserHandle.of(100);
    private static final UserHandle PRIVATE_USER_HANDLE = UserHandle.of(101);

    @Mock
    private JavaAdapter mJavaAdapter;
    @Mock
    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
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
    private Resources mResources;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private UiModeManager mUiModeManager;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiver;
    @Captor
    private ArgumentCaptor<WallpaperManager.OnColorsChangedListener> mColorsListener;
    @Captor
    private ArgumentCaptor<DeviceProvisionedListener> mDeviceProvisionedListener;
    @Captor
    private ArgumentCaptor<WakefulnessLifecycle.Observer> mWakefulnessLifecycleObserver;
    @Captor
    private ArgumentCaptor<UserTracker.Callback> mUserTrackerCallback;
    @Captor
    private ArgumentCaptor<ContentObserver> mSettingsObserver;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mFeatureFlags.isEnabled(Flags.MONET)).thenReturn(true);
        when(mWakefulnessLifecycle.getWakefulness()).thenReturn(WAKEFULNESS_AWAKE);
        when(mUiModeManager.getContrast()).thenReturn(0.5f);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        when(mResources.getColor(eq(android.R.color.system_accent1_500), any()))
                .thenReturn(Color.RED);
        when(mResources.getColor(eq(android.R.color.system_accent2_500), any()))
                .thenReturn(Color.GREEN);
        when(mResources.getColor(eq(android.R.color.system_accent3_500), any()))
                .thenReturn(Color.BLUE);
        when(mResources.getColor(eq(android.R.color.system_neutral1_500), any()))
                .thenReturn(Color.YELLOW);
        when(mResources.getColor(eq(android.R.color.system_neutral2_500), any()))
                .thenReturn(Color.BLACK);

        mThemeOverlayController = new ThemeOverlayController(mContext,
                mBroadcastDispatcher, mBgHandler, mMainExecutor, mBgExecutor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mDeviceProvisionedController,
                mUserTracker, mDumpManager, mFeatureFlags, mResources, mWakefulnessLifecycle,
                mJavaAdapter, mKeyguardTransitionInteractor, mUiModeManager) {
            @VisibleForTesting
            protected boolean isNightMode() {
                return false;
            }

            @VisibleForTesting
            protected FabricatedOverlay newFabricatedOverlay(String name) {
                FabricatedOverlay overlay = mock(FabricatedOverlay.class);
                when(overlay.getIdentifier())
                        .thenReturn(new OverlayIdentifier(
                                Integer.toHexString(mColorScheme.getSeed() | 0xff000000)));
                return overlay;
            }

            @VisibleForTesting
            protected boolean isPrivateProfile(UserHandle userHandle) {
                if (userHandle.getIdentifier() == PRIVATE_USER_HANDLE.getIdentifier()) {
                    return true;
                }
                return false;
            }
        };

        mWakefulnessLifecycle.dispatchFinishedWakingUp();
        mThemeOverlayController.start();
        verify(mUserTracker).addCallback(mUserTrackerCallback.capture(), eq(mBgExecutor));
        verify(mWallpaperManager).addOnColorsChangedListener(mColorsListener.capture(), eq(null),
                eq(UserHandle.USER_ALL));
        verify(mBroadcastDispatcher).registerReceiver(mBroadcastReceiver.capture(), any(),
                eq(mMainExecutor), any());
        verify(mWakefulnessLifecycle).addObserver(mWakefulnessLifecycleObserver.capture());
        verify(mDumpManager).registerDumpable(any(), any());
        verify(mDeviceProvisionedController).addCallback(mDeviceProvisionedListener.capture());
        verify(mSecureSettings).registerContentObserverForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                eq(false), mSettingsObserver.capture(), eq(UserHandle.USER_ALL)
        );
    }

    @Test
    public void start_checksWallpaper() {
        ArgumentCaptor<Runnable> registrationRunnable = ArgumentCaptor.forClass(Runnable.class);
        verify(mBgExecutor).execute(registrationRunnable.capture());

        registrationRunnable.getValue().run();
        verify(mWallpaperManager).getWallpaperColors(eq(WallpaperManager.FLAG_SYSTEM));
    }

    @Test
    public void onWallpaperColorsChanged_setsTheme_whenForeground() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(new OverlayIdentifier("ffff0000"));
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_ACCENT_COLOR))
                .isEqualTo(new OverlayIdentifier("ffff0000"));

        // Should not ask again if changed to same value
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        verifyNoMoreInteractions(mThemeOverlayApplier);

        // Should not ask again even for new colors until we change wallpapers
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.BLACK),
                null, null), WallpaperManager.FLAG_SYSTEM, USER_SYSTEM);
        verifyNoMoreInteractions(mThemeOverlayApplier);

        // But should change theme after changing wallpapers
        clearInvocations(mThemeOverlayApplier);
        Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        intent.putExtra(WallpaperManager.EXTRA_FROM_FOREGROUND_APP, true);
        mBroadcastReceiver.getValue().onReceive(null, intent);
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.BLACK),
                null, null), WallpaperManager.FLAG_SYSTEM, USER_SYSTEM);
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_setsTheme_skipWhenBackground() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any());

        // Should not change theme after changing wallpapers, if intent doesn't have
        // WallpaperManager.EXTRA_FROM_FOREGROUND_APP set to true.
        clearInvocations(mThemeOverlayApplier);
        mBroadcastReceiver.getValue().onReceive(null, new Intent(Intent.ACTION_WALLPAPER_CHANGED));
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.BLACK),
                null, null), WallpaperManager.FLAG_SYSTEM, USER_SYSTEM);
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
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

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any());

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
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());

        assertThat(updatedSetting.getValue().contains("android.theme.customization.accent_color"))
                .isFalse();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.system_palette"))
                .isFalse();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.color_index"))
                .isFalse();

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_resetThemeWithNewHomeWallpapers() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(20);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, USER_SYSTEM))
                .thenReturn(21);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());

        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_both\":\"0")).isTrue();

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_keepsThemeWhenSetFromLockScreen() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        String jsonString =
                "{\"android.theme.customization.color_source\":\"lock_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(20);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, USER_SYSTEM))
                .thenReturn(21);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        verify(mSecureSettings, never()).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), any(), anyInt());
    }

    @Test
    public void onWallpaperColorsChanged_resetLockScreenThemeWhenBothSet() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        String jsonString =
                "{\"android.theme.customization.color_source\":\"lock_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(20);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, USER_SYSTEM))
                .thenReturn(21);

        mColorsListener.getValue().onColorsChanged(mainColors,
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());
        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_both\":\"1")).isTrue();
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onSettingChanged_honorThemeStyle() {
        when(mDeviceProvisionedController.isUserSetup(anyInt())).thenReturn(true);
        List<Style> validStyles = Arrays.asList(Style.EXPRESSIVE, Style.SPRITZ, Style.TONAL_SPOT,
                Style.FRUIT_SALAD, Style.RAINBOW, Style.VIBRANT);
        for (Style style : validStyles) {
            reset(mSecureSettings);

            String jsonString = "{\"android.theme.customization.system_palette\":\"A16B00\","
                    + "\"android.theme.customization.theme_style\":\"" + style.name() + "\"}";

            when(mSecureSettings.getStringForUser(
                    eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                    .thenReturn(jsonString);

            mSettingsObserver.getValue().onChange(true, null, 0, mUserTracker.getUserId());

            assertThat(mThemeOverlayController.mThemeStyle).isEqualTo(style);
        }
    }

    @Test
    public void onSettingChanged_invalidStyle() {
        when(mDeviceProvisionedController.isUserSetup(anyInt())).thenReturn(true);
        String jsonString = "{\"android.theme.customization.system_palette\":\"A16B00\","
                + "\"android.theme.customization.theme_style\":\"some_invalid_name\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);

        mSettingsObserver.getValue().onChange(true, null, 0, mUserTracker.getUserId());

        assertThat(mThemeOverlayController.mThemeStyle).isEqualTo(Style.TONAL_SPOT);
    }

    @Test
    public void onWallpaperColorsChanged_resetThemeWithNewHomeAndLockWallpaper() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(-1);

        mColorsListener.getValue().onColorsChanged(mainColors,
                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK, USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());

        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_both\":\"1")).isTrue();

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_changeLockWallpaper() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(1);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_LOCK,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());
        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_source\":\"lock_wallpaper")).isTrue();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.color_index"))
                .isFalse();
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_changeHomeWallpaper() {
        // Should ask for a new theme when wallpaper colors change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";
        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(-1);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());
        assertThat(updatedSetting.getValue().contains(
                "android.theme.customization.color_source\":\"home_wallpaper")).isTrue();
        assertThat(updatedSetting.getValue().contains("android.theme.customization.color_index"))
                .isFalse();
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_resetThemeWhenFromLatestWallpaper() {
        // Should ask for a new theme when the colors of the last applied wallpaper change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(1);
        // SYSTEM wallpaper is the last applied one
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, USER_SYSTEM))
                .thenReturn(2);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings).putStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture(),
                anyInt());

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_keepThemeWhenFromLatestWallpaperAndSpecifiedColor() {
        // Shouldn't ask for a new theme when the colors of the last applied wallpaper change
        // with the same specified system palette one.
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(0xffa16b00), null);

        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(1);
        // SYSTEM wallpaper is the last applied one
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, USER_SYSTEM))
                .thenReturn(2);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        ArgumentCaptor<String> updatedSetting = ArgumentCaptor.forClass(String.class);
        verify(mSecureSettings, never()).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), updatedSetting.capture());

        // Apply overlay by existing theme from secure setting
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_keepThemeIfNotLatestWallpaper() {
        // Shouldn't ask for a new theme when the colors of the wallpaper that is not the last
        // applied one change
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);

        String jsonString =
                "{\"android.theme.customization.color_source\":\"home_wallpaper\","
                        + "\"android.theme.customization.system_palette\":\"A16B00\","
                        + "\"android.theme.customization.accent_color\":\"A16B00\","
                        + "\"android.theme.customization.color_index\":\"2\"}";

        when(mSecureSettings.getStringForUser(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), anyInt()))
                .thenReturn(jsonString);
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, USER_SYSTEM))
                .thenReturn(1);
        // SYSTEM wallpaper is the last applied one
        when(mWallpaperManager.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, USER_SYSTEM))
                .thenReturn(2);

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_LOCK,
                USER_SYSTEM);

        verify(mSecureSettings, never()).putString(
                eq(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES), any());


        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onUserSwitching_setsTheme() {
        // Setup users with different colors
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED), null, null);
        WallpaperColors secondaryColors =
                new WallpaperColors(Color.valueOf(Color.BLUE), null, null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        mColorsListener.getValue().onColorsChanged(secondaryColors, WallpaperManager.FLAG_SYSTEM,
                USER_SECONDARY);

        // When changing users
        clearInvocations(mThemeOverlayApplier);
        when(mUserTracker.getUserId()).thenReturn(USER_SECONDARY);
        mUserTrackerCallback.getValue().onUserChanged(USER_SECONDARY, mContext);

        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any());

        // Assert that we received secondary user colors
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(new OverlayIdentifier("ff0000ff"));
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_ACCENT_COLOR))
                .isEqualTo(new OverlayIdentifier("ff0000ff"));
    }

    @Test
    public void onProfileAdded_setsTheme() {
        mBroadcastReceiver.getValue().onReceive(null,
                new Intent(Intent.ACTION_PROFILE_ADDED)
                        .putExtra(Intent.EXTRA_USER, MANAGED_USER_HANDLE));
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onUserAdded_appliesTheme_ifNotManagedProfile() {
        reset(mDeviceProvisionedController);
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(false);
        mBroadcastReceiver.getValue().onReceive(null,
                new Intent(Intent.ACTION_PROFILE_ADDED)
                        .putExtra(Intent.EXTRA_USER, MANAGED_USER_HANDLE));
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onProfileAdded_ignoresUntilSetupComplete() {
        reset(mDeviceProvisionedController);
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(true);
        mBroadcastReceiver.getValue().onReceive(null,
                new Intent(Intent.ACTION_PROFILE_ADDED)
                        .putExtra(Intent.EXTRA_USER, MANAGED_USER_HANDLE));
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onPrivateProfileAdded_ignoresUntilStartComplete() {
        mSetFlagsRule.enableFlags(FLAG_ALLOW_PRIVATE_PROFILE);
        reset(mDeviceProvisionedController);
        when(mUserManager.isManagedProfile(anyInt())).thenReturn(false);
        mBroadcastReceiver.getValue().onReceive(null,
                (new Intent(Intent.ACTION_PROFILE_ADDED))
                        .putExtra(Intent.EXTRA_USER, PRIVATE_USER_HANDLE));
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }


    @Test
    public void onWallpaperColorsChanged_firstEventBeforeUserSetup_shouldBeAccepted() {
        // By default, on setup() we make this controller return that the user finished setup
        // wizard. This test on the other hand, is testing the setup flow.
        reset(mDeviceProvisionedController);
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());

        // Regression test: null events should not reset the internal state and allow colors to be
        // applied again.
        clearInvocations(mThemeOverlayApplier);
        Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        intent.putExtra(WallpaperManager.EXTRA_FROM_FOREGROUND_APP, true);
        mBroadcastReceiver.getValue().onReceive(null, intent);
        mColorsListener.getValue().onColorsChanged(null, WallpaperManager.FLAG_SYSTEM, USER_SYSTEM);
        verify(mThemeOverlayApplier, never()).applyCurrentUserOverlays(any(), any(), anyInt(),
                any());
        mColorsListener.getValue().onColorsChanged(new WallpaperColors(Color.valueOf(Color.GREEN),
                null, null), WallpaperManager.FLAG_SYSTEM, USER_SYSTEM);
        verify(mThemeOverlayApplier, never()).applyCurrentUserOverlays(any(), any(), anyInt(),
                any());
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

        mThemeOverlayController = new ThemeOverlayController(mContext,
                mBroadcastDispatcher, mBgHandler, executor, executor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mDeviceProvisionedController,
                mUserTracker, mDumpManager, mFeatureFlags, mResources, mWakefulnessLifecycle,
                mJavaAdapter, mKeyguardTransitionInteractor, mUiModeManager) {
            @VisibleForTesting
            protected boolean isNightMode() {
                return false;
            }

            @VisibleForTesting
            protected FabricatedOverlay newFabricatedOverlay(String name) {
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
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
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
        mThemeOverlayController = new ThemeOverlayController(mContext,
                mBroadcastDispatcher, mBgHandler, executor, executor, mThemeOverlayApplier,
                mSecureSettings, mWallpaperManager, mUserManager, mDeviceProvisionedController,
                mUserTracker, mDumpManager, mFeatureFlags, mResources, mWakefulnessLifecycle,
                mJavaAdapter, mKeyguardTransitionInteractor, mUiModeManager) {
            @VisibleForTesting
            protected boolean isNightMode() {
                return false;
            }

            @VisibleForTesting
            protected FabricatedOverlay newFabricatedOverlay(String name) {
                FabricatedOverlay overlay = mock(FabricatedOverlay.class);
                when(overlay.getIdentifier())
                        .thenReturn(new OverlayIdentifier(
                                Integer.toHexString(mColorScheme.getSeed() | 0xff000000)));
                return overlay;
            }
        };
        mThemeOverlayController.start();
        verify(mWallpaperManager).addOnColorsChangedListener(mColorsListener.capture(), eq(null),
                eq(UserHandle.USER_ALL));
        verify(mDeviceProvisionedController).addCallback(mDeviceProvisionedListener.capture());

        // Colors were applied during controller initialization.
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
        clearInvocations(mThemeOverlayApplier);

        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        reset(mResources);
        when(mResources.getColor(eq(android.R.color.system_accent1_500), any()))
                .thenReturn(mThemeOverlayController.mColorScheme.getAccent1().getS500());
        when(mResources.getColor(eq(android.R.color.system_accent2_500), any()))
                .thenReturn(mThemeOverlayController.mColorScheme.getAccent2().getS500());
        when(mResources.getColor(eq(android.R.color.system_accent3_500), any()))
                .thenReturn(mThemeOverlayController.mColorScheme.getAccent3().getS500());
        when(mResources.getColor(eq(android.R.color.system_neutral1_500), any()))
                .thenReturn(mThemeOverlayController.mColorScheme.getNeutral1().getS500());
        when(mResources.getColor(eq(android.R.color.system_neutral2_500), any()))
                .thenReturn(mThemeOverlayController.mColorScheme.getNeutral2().getS500());

        // Defers event because we already have initial colors.
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());

        // Then event happens after setup phase is over.
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mDeviceProvisionedListener.getValue().onUserSetupChanged();
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_screenOff_deviceSetupNotFinished_doesNotProcessQueued() {
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);
        mDeviceProvisionedListener.getValue().onUserSetupChanged();


        // Second color application is not applied.
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        clearInvocations(mThemeOverlayApplier);

        // Device went to sleep and second set of colors was applied.
        mainColors =  new WallpaperColors(Color.valueOf(Color.BLUE),
                Color.valueOf(Color.RED), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());

        mWakefulnessLifecycle.dispatchFinishedGoingToSleep();
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
    }

    @Test
    public void onWallpaperColorsChanged_screenOff_processesQueued() {
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mDeviceProvisionedListener.getValue().onUserSetupChanged();

        // Second color application is not applied.
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);

        clearInvocations(mThemeOverlayApplier);

        // Device went to sleep and second set of colors was applied.
        mainColors =  new WallpaperColors(Color.valueOf(Color.BLUE),
                Color.valueOf(Color.RED), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());

        mWakefulnessLifecycleObserver.getValue().onFinishedGoingToSleep();
        verify(mThemeOverlayApplier).applyCurrentUserOverlays(any(), any(), anyInt(), any());
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

        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        ArgumentCaptor<Map<String, OverlayIdentifier>> themeOverlays =
                ArgumentCaptor.forClass(Map.class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(themeOverlays.capture(), any(), anyInt(), any());

        // Assert that we received the colors that we were expecting
        assertThat(themeOverlays.getValue().get(OVERLAY_CATEGORY_SYSTEM_PALETTE))
                .isEqualTo(new OverlayIdentifier("ff00ff00"));
    }

    // Regression test for b/234603929, where a reboot would generate a wallpaper colors changed
    // event for the already-set colors that would then set the theme incorrectly on screen sleep.
    @Test
    public void onWallpaperColorsSetToSame_keepsTheme() {
        // Set initial colors and verify.
        WallpaperColors startingColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        WallpaperColors sameColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(startingColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());
        clearInvocations(mThemeOverlayApplier);

        // Set to the same colors.
        mColorsListener.getValue().onColorsChanged(sameColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        verify(mThemeOverlayApplier, never())
                .applyCurrentUserOverlays(any(), any(), anyInt(), any());

        // Verify that no change resulted.
        mWakefulnessLifecycleObserver.getValue().onFinishedGoingToSleep();
        verify(mThemeOverlayApplier, never()).applyCurrentUserOverlays(any(), any(), anyInt(),
                any());
    }

    @Test
    public void createDynamicOverlay_addsAllDynamicColors() {
        // Trigger new wallpaper colors to generate an overlay
        WallpaperColors mainColors = new WallpaperColors(Color.valueOf(Color.RED),
                Color.valueOf(Color.BLUE), null);
        mColorsListener.getValue().onColorsChanged(mainColors, WallpaperManager.FLAG_SYSTEM,
                USER_SYSTEM);
        ArgumentCaptor<FabricatedOverlay[]> themeOverlays =
                ArgumentCaptor.forClass(FabricatedOverlay[].class);

        verify(mThemeOverlayApplier)
                .applyCurrentUserOverlays(any(), themeOverlays.capture(), anyInt(), any());

        FabricatedOverlay[] overlays = themeOverlays.getValue();
        FabricatedOverlay accents = overlays[0];
        FabricatedOverlay neutrals = overlays[1];
        FabricatedOverlay dynamic = overlays[2];

        final int colorsPerPalette = 12;

        // Color resources were added for all 3 accent palettes
        verify(accents, times(colorsPerPalette * 3))
                .setResourceValue(any(String.class), eq(TYPE_INT_COLOR_ARGB8), anyInt(), eq(null));
        // Color resources were added for all 2 neutral palettes
        verify(neutrals, times(colorsPerPalette * 2))
                .setResourceValue(any(String.class), eq(TYPE_INT_COLOR_ARGB8), anyInt(), eq(null));
        // All dynamic colors were added twice: light and dark them
        // All fixed colors were added once
        verify(dynamic, times(
                DynamicColors.allDynamicColorsMapped(false).size() * 2
                        + DynamicColors.getFixedColorsMapped(false).size())
        ).setResourceValue(any(String.class), eq(TYPE_INT_COLOR_ARGB8), anyInt(), eq(null));
    }
}
