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

import static com.android.server.notification.ZenLog.traceApplyDeviceEffect;
import static com.android.server.notification.ZenLog.traceScheduleApplyDeviceEffect;

import android.app.KeyguardManager;
import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.ColorDisplayManager;
import android.os.Binder;
import android.os.PowerManager;
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ConfigOrigin;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

/** Default implementation for {@link DeviceEffectsApplier}. */
class DefaultDeviceEffectsApplier implements DeviceEffectsApplier {
    private static final String TAG = "DeviceEffectsApplier";
    private static final String SUPPRESS_AMBIENT_DISPLAY_TOKEN =
            "DefaultDeviceEffectsApplier:SuppressAmbientDisplay";
    private static final int SATURATION_LEVEL_GRAYSCALE = 0;
    private static final int SATURATION_LEVEL_FULL_COLOR = 100;
    private static final float WALLPAPER_DIM_AMOUNT_DIMMED = 0.6f;
    private static final float WALLPAPER_DIM_AMOUNT_NORMAL = 0f;
    private static final IntentFilter SCREEN_OFF_INTENT_FILTER = new IntentFilter(
            Intent.ACTION_SCREEN_OFF);

    private final Context mContext;
    private final ColorDisplayManager mColorDisplayManager;
    private final KeyguardManager mKeyguardManager;
    private final PowerManager mPowerManager;
    private final UiModeManager mUiModeManager;
    private final WallpaperManager mWallpaperManager;

    private final Object mRegisterReceiverLock = new Object();
    @GuardedBy("mRegisterReceiverLock")
    private boolean mIsScreenOffReceiverRegistered;

    private ZenDeviceEffects mLastAppliedEffects = new ZenDeviceEffects.Builder().build();
    private boolean mPendingNightMode;

    DefaultDeviceEffectsApplier(Context context) {
        mContext = context;
        mColorDisplayManager = context.getSystemService(ColorDisplayManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mUiModeManager = context.getSystemService(UiModeManager.class);
        WallpaperManager wallpaperManager = context.getSystemService(WallpaperManager.class);
        mWallpaperManager = wallpaperManager != null && wallpaperManager.isWallpaperSupported()
                ? wallpaperManager : null;
    }

    @Override
    public void apply(ZenDeviceEffects effects, @ConfigOrigin int origin) {
        Binder.withCleanCallingIdentity(() -> {
            if (mLastAppliedEffects.shouldSuppressAmbientDisplay()
                    != effects.shouldSuppressAmbientDisplay()) {
                try {
                    traceApplyDeviceEffect("suppressAmbientDisplay",
                            effects.shouldSuppressAmbientDisplay());
                    mPowerManager.suppressAmbientDisplay(SUPPRESS_AMBIENT_DISPLAY_TOKEN,
                            effects.shouldSuppressAmbientDisplay());
                } catch (Exception e) {
                    Slog.e(TAG, "Could not change AOD override", e);
                }
            }

            if (mLastAppliedEffects.shouldDisplayGrayscale() != effects.shouldDisplayGrayscale()) {
                if (mColorDisplayManager != null) {
                    try {
                        traceApplyDeviceEffect("displayGrayscale",
                                effects.shouldDisplayGrayscale());
                        mColorDisplayManager.setSaturationLevel(
                                effects.shouldDisplayGrayscale() ? SATURATION_LEVEL_GRAYSCALE
                                        : SATURATION_LEVEL_FULL_COLOR);
                    } catch (Exception e) {
                        Slog.e(TAG, "Could not change grayscale override", e);
                    }
                }
            }

            if (mLastAppliedEffects.shouldDimWallpaper() != effects.shouldDimWallpaper()) {
                if (mWallpaperManager != null) {
                    try {
                        traceApplyDeviceEffect("dimWallpaper", effects.shouldDimWallpaper());
                        mWallpaperManager.setWallpaperDimAmount(
                                effects.shouldDimWallpaper() ? WALLPAPER_DIM_AMOUNT_DIMMED
                                        : WALLPAPER_DIM_AMOUNT_NORMAL);
                    } catch (Exception e) {
                        Slog.e(TAG, "Could not change wallpaper override", e);
                    }
                }
            }

            if (mLastAppliedEffects.shouldUseNightMode() != effects.shouldUseNightMode()) {
                try {
                    updateOrScheduleNightMode(effects.shouldUseNightMode(), origin);
                } catch (Exception e) {
                    Slog.e(TAG, "Could not change dark theme override", e);
                }
            }
        });

        mLastAppliedEffects = effects;
    }

    private void updateOrScheduleNightMode(boolean useNightMode, @ConfigOrigin int origin) {
        mPendingNightMode = useNightMode;

        // Changing the theme can be disruptive for the user (Activities are likely recreated, may
        // lose some state). Therefore we only apply the change immediately if the rule was
        // activated manually, or we are initializing, or the screen is currently off/dreaming,
        // or if the device is locked.
        if (origin == ZenModeConfig.ORIGIN_INIT
                || origin == ZenModeConfig.ORIGIN_INIT_USER
                || origin == ZenModeConfig.ORIGIN_USER_IN_SYSTEMUI
                || origin == ZenModeConfig.ORIGIN_USER_IN_APP
                || !mPowerManager.isInteractive()
                || (android.app.Flags.modesUi() && mKeyguardManager.isKeyguardLocked())) {
            unregisterScreenOffReceiver();
            updateNightModeImmediately(useNightMode);
        } else {
            traceScheduleApplyDeviceEffect("nightMode", useNightMode);
            registerScreenOffReceiver();
        }
    }

    @GuardedBy("mRegisterReceiverLock")
    private final BroadcastReceiver mNightModeWhenScreenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            unregisterScreenOffReceiver();
            updateNightModeImmediately(mPendingNightMode);
        }
    };

    private void updateNightModeImmediately(boolean useNightMode) {
        Binder.withCleanCallingIdentity(() -> {
            try {
                traceApplyDeviceEffect("nightMode", useNightMode);
                mUiModeManager.setAttentionModeThemeOverlay(
                        useNightMode ? MODE_ATTENTION_THEME_OVERLAY_NIGHT
                                : MODE_ATTENTION_THEME_OVERLAY_OFF);
            } catch (Exception e) {
                Slog.e(TAG, "Could not change wallpaper override", e);
            }
        });
    }

    private void registerScreenOffReceiver() {
        synchronized (mRegisterReceiverLock) {
            if (!mIsScreenOffReceiverRegistered) {
                mContext.registerReceiver(mNightModeWhenScreenOff, SCREEN_OFF_INTENT_FILTER,
                        Context.RECEIVER_NOT_EXPORTED);
                mIsScreenOffReceiverRegistered = true;
            }
        }
    }

    private void unregisterScreenOffReceiver() {
        synchronized (mRegisterReceiverLock) {
            if (mIsScreenOffReceiverRegistered) {
                mIsScreenOffReceiverRegistered = false;
                mContext.unregisterReceiver(mNightModeWhenScreenOff);
            }
        }
    }
}
