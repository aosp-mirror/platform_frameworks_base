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

import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_BEDTIME;

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
import android.service.notification.ZenModeConfig.ConfigChangeOrigin;

import com.android.internal.annotations.GuardedBy;

/** Default implementation for {@link DeviceEffectsApplier}. */
class DefaultDeviceEffectsApplier implements DeviceEffectsApplier {

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
        mPowerManager = context.getSystemService(PowerManager.class);
        mUiModeManager = context.getSystemService(UiModeManager.class);
        mWallpaperManager = context.getSystemService(WallpaperManager.class);
    }

    @Override
    public void apply(ZenDeviceEffects effects, @ConfigChangeOrigin int origin) {
        Binder.withCleanCallingIdentity(() -> {
            if (mLastAppliedEffects.shouldSuppressAmbientDisplay()
                    != effects.shouldSuppressAmbientDisplay()) {
                mPowerManager.suppressAmbientDisplay(SUPPRESS_AMBIENT_DISPLAY_TOKEN,
                        effects.shouldSuppressAmbientDisplay());
            }

            if (mLastAppliedEffects.shouldDisplayGrayscale() != effects.shouldDisplayGrayscale()) {
                if (mColorDisplayManager != null) {
                    mColorDisplayManager.setSaturationLevel(
                            effects.shouldDisplayGrayscale() ? SATURATION_LEVEL_GRAYSCALE
                                    : SATURATION_LEVEL_FULL_COLOR);
                }
            }

            if (mLastAppliedEffects.shouldDimWallpaper() != effects.shouldDimWallpaper()) {
                if (mWallpaperManager != null) {
                    mWallpaperManager.setWallpaperDimAmount(
                            effects.shouldDimWallpaper() ? WALLPAPER_DIM_AMOUNT_DIMMED
                                    : WALLPAPER_DIM_AMOUNT_NORMAL);
                }
            }

            if (mLastAppliedEffects.shouldUseNightMode() != effects.shouldUseNightMode()) {
                updateOrScheduleNightMode(effects.shouldUseNightMode(), origin);
            }
        });

        mLastAppliedEffects = effects;
    }

    private void updateOrScheduleNightMode(boolean useNightMode, @ConfigChangeOrigin int origin) {
        mPendingNightMode = useNightMode;

        // Changing the theme can be disruptive for the user (Activities are likely recreated, may
        // lose some state). Therefore we only apply the change immediately if the rule was
        // activated manually, or we are initializing, or the screen is currently off/dreaming.
        if (origin == ZenModeConfig.UPDATE_ORIGIN_INIT
                || origin == ZenModeConfig.UPDATE_ORIGIN_INIT_USER
                || origin == ZenModeConfig.UPDATE_ORIGIN_USER
                || origin == ZenModeConfig.UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI
                || !mPowerManager.isInteractive()) {
            unregisterScreenOffReceiver();
            updateNightModeImmediately(useNightMode);
        } else {
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
            // TODO: b/314285749 - Placeholder; use real APIs when available.
            mUiModeManager.setNightModeCustomType(MODE_NIGHT_CUSTOM_TYPE_BEDTIME);
            mUiModeManager.setNightModeActivatedForCustomMode(MODE_NIGHT_CUSTOM_TYPE_BEDTIME,
                    useNightMode);
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
