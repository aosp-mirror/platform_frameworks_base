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

import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.os.Binder;
import android.os.PowerManager;
import android.service.notification.DeviceEffectsApplier;
import android.service.notification.ZenDeviceEffects;

/** Default implementation for {@link DeviceEffectsApplier}. */
class DefaultDeviceEffectsApplier implements DeviceEffectsApplier {

    private static final String SUPPRESS_AMBIENT_DISPLAY_TOKEN =
            "DefaultDeviceEffectsApplier:SuppressAmbientDisplay";
    private static final int SATURATION_LEVEL_GRAYSCALE = 0;
    private static final int SATURATION_LEVEL_FULL_COLOR = 100;
    private static final float WALLPAPER_DIM_AMOUNT_DIMMED = 0.6f;
    private static final float WALLPAPER_DIM_AMOUNT_NORMAL = 0f;

    private final ColorDisplayManager mColorDisplayManager;
    private final PowerManager mPowerManager;
    private final UiModeManager mUiModeManager;
    private final WallpaperManager mWallpaperManager;

    DefaultDeviceEffectsApplier(Context context) {
        mColorDisplayManager = context.getSystemService(ColorDisplayManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mUiModeManager = context.getSystemService(UiModeManager.class);
        mWallpaperManager = context.getSystemService(WallpaperManager.class);
    }

    @Override
    public void apply(ZenDeviceEffects effects) {
        Binder.withCleanCallingIdentity(() -> {
            mPowerManager.suppressAmbientDisplay(SUPPRESS_AMBIENT_DISPLAY_TOKEN,
                    effects.shouldSuppressAmbientDisplay());

            if (mColorDisplayManager != null) {
                mColorDisplayManager.setSaturationLevel(
                        effects.shouldDisplayGrayscale() ? SATURATION_LEVEL_GRAYSCALE
                                : SATURATION_LEVEL_FULL_COLOR);
            }

            if (mWallpaperManager != null) {
                mWallpaperManager.setWallpaperDimAmount(
                        effects.shouldDimWallpaper() ? WALLPAPER_DIM_AMOUNT_DIMMED
                                : WALLPAPER_DIM_AMOUNT_NORMAL);
            }

            // TODO: b/308673343 - Apply dark theme (via UiModeManager) when screen is off.
        });
    }
}
