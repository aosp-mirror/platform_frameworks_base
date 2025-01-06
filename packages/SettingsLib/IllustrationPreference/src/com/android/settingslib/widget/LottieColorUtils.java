/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;

import androidx.annotation.NonNull;

import com.android.settingslib.widget.theme.R;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import java.util.Map;

/**
 * Util class which dynamically changes the color of tags in a lottie json file between Dark Theme
 * (DT) and Light Theme (LT). This class assumes the json file is for Dark Theme.
 */
public class LottieColorUtils {
    private static final Map<String, Integer> DARK_TO_LIGHT_THEME_COLOR_MAP;
    private static final Map<String, Integer> MATERIAL_COLOR_MAP;

    static {
        DARK_TO_LIGHT_THEME_COLOR_MAP = Map.ofEntries(
                Map.entry(".grey200",
                        com.android.settingslib.color.R.color.settingslib_color_grey800),
                Map.entry(".grey600",
                        com.android.settingslib.color.R.color.settingslib_color_grey400),
                Map.entry(".grey800",
                        com.android.settingslib.color.R.color.settingslib_color_grey300),
                Map.entry(".grey900",
                        com.android.settingslib.color.R.color.settingslib_color_grey50),
                Map.entry(".red100",
                        com.android.settingslib.color.R.color.settingslib_color_red500),
                Map.entry(".red200",
                        com.android.settingslib.color.R.color.settingslib_color_red500),
                Map.entry(".red400",
                        com.android.settingslib.color.R.color.settingslib_color_red600),
                Map.entry(".black",
                        android.R.color.white),
                Map.entry(".blue200",
                        com.android.settingslib.color.R.color.settingslib_color_blue700),
                Map.entry(".blue400",
                        com.android.settingslib.color.R.color.settingslib_color_blue600),
                Map.entry(".green100",
                        com.android.settingslib.color.R.color.settingslib_color_green500),
                Map.entry(".green200",
                        com.android.settingslib.color.R.color.settingslib_color_green500),
                Map.entry(".green400",
                        com.android.settingslib.color.R.color.settingslib_color_green600),
                Map.entry(".cream",
                        com.android.settingslib.color.R.color.settingslib_color_charcoal));

        MATERIAL_COLOR_MAP = Map.ofEntries(
                Map.entry(".primary", R.color.settingslib_materialColorPrimary),
                Map.entry(".onPrimary", R.color.settingslib_materialColorOnPrimary),
                Map.entry(".primaryContainer", R.color.settingslib_materialColorPrimaryContainer),
                Map.entry(".onPrimaryContainer",
                        R.color.settingslib_materialColorOnPrimaryContainer),
                Map.entry(".primaryInverse", R.color.settingslib_materialColorPrimaryInverse),
                Map.entry(".primaryFixed", R.color.settingslib_materialColorPrimaryFixed),
                Map.entry(".primaryFixedDim", R.color.settingslib_materialColorPrimaryFixedDim),
                Map.entry(".onPrimaryFixed", R.color.settingslib_materialColorOnPrimaryFixed),
                Map.entry(".onPrimaryFixedVariant",
                        R.color.settingslib_materialColorOnPrimaryFixedVariant),
                Map.entry(".secondary", R.color.settingslib_materialColorSecondary),
                Map.entry(".onSecondary", R.color.settingslib_materialColorOnSecondary),
                Map.entry(".secondaryContainer",
                        R.color.settingslib_materialColorSecondaryContainer),
                Map.entry(".onSecondaryContainer",
                        R.color.settingslib_materialColorOnSecondaryContainer),
                Map.entry(".secondaryFixed", R.color.settingslib_materialColorSecondaryFixed),
                Map.entry(".secondaryFixedDim", R.color.settingslib_materialColorSecondaryFixedDim),
                Map.entry(".onSecondaryFixed", R.color.settingslib_materialColorOnSecondaryFixed),
                Map.entry(".onSecondaryFixedVariant",
                        R.color.settingslib_materialColorOnSecondaryFixedVariant),
                Map.entry(".tertiary", R.color.settingslib_materialColorTertiary),
                Map.entry(".onTertiary", R.color.settingslib_materialColorOnTertiary),
                Map.entry(".tertiaryContainer", R.color.settingslib_materialColorTertiaryContainer),
                Map.entry(".onTertiaryContainer",
                        R.color.settingslib_materialColorOnTertiaryContainer),
                Map.entry(".tertiaryFixed", R.color.settingslib_materialColorTertiaryFixed),
                Map.entry(".tertiaryFixedDim", R.color.settingslib_materialColorTertiaryFixedDim),
                Map.entry(".onTertiaryFixed", R.color.settingslib_materialColorOnTertiaryFixed),
                Map.entry(".onTertiaryFixedVariant",
                        R.color.settingslib_materialColorOnTertiaryFixedVariant),
                Map.entry(".error", R.color.settingslib_materialColorError),
                Map.entry(".onError", R.color.settingslib_materialColorOnError),
                Map.entry(".errorContainer", R.color.settingslib_materialColorErrorContainer),
                Map.entry(".onErrorContainer", R.color.settingslib_materialColorOnErrorContainer),
                Map.entry(".outline", R.color.settingslib_materialColorOutline),
                Map.entry(".outlineVariant", R.color.settingslib_materialColorOutlineVariant),
                Map.entry(".background", R.color.settingslib_materialColorBackground),
                Map.entry(".onBackground", R.color.settingslib_materialColorOnBackground),
                Map.entry(".surface", R.color.settingslib_materialColorSurface),
                Map.entry(".onSurface", R.color.settingslib_materialColorOnSurface),
                Map.entry(".surfaceVariant", R.color.settingslib_materialColorSurfaceVariant),
                Map.entry(".onSurfaceVariant", R.color.settingslib_materialColorOnSurfaceVariant),
                Map.entry(".surfaceInverse", R.color.settingslib_materialColorSurfaceInverse),
                Map.entry(".onSurfaceInverse", R.color.settingslib_materialColorOnSurfaceInverse),
                Map.entry(".surfaceBright", R.color.settingslib_materialColorSurfaceBright),
                Map.entry(".surfaceDim", R.color.settingslib_materialColorSurfaceDim),
                Map.entry(".surfaceContainer", R.color.settingslib_materialColorSurfaceContainer),
                Map.entry(".surfaceContainerLow",
                        R.color.settingslib_materialColorSurfaceContainerLow),
                Map.entry(".surfaceContainerLowest",
                        R.color.settingslib_materialColorSurfaceContainerLowest),
                Map.entry(".surfaceContainerHigh",
                        R.color.settingslib_materialColorSurfaceContainerHigh),
                Map.entry(".surfaceContainerHighest",
                        R.color.settingslib_materialColorSurfaceContainerHighest));
    }

    private LottieColorUtils() {
    }

    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Applies dynamic colors based on DT vs. LT. The LottieAnimationView should be Dark Theme. */
    public static void applyDynamicColors(Context context,
            LottieAnimationView lottieAnimationView) {
        // Assume the default for the lottie is dark mode
        if (isDarkMode(context)) {
            return;
        }
        for (String key : DARK_TO_LIGHT_THEME_COLOR_MAP.keySet()) {
            final int color = context.getColor(DARK_TO_LIGHT_THEME_COLOR_MAP.get(key));
            lottieAnimationView.addValueCallback(
                    new KeyPath("**", key, "**"),
                    LottieProperty.COLOR_FILTER,
                    frameInfo -> new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP));
        }
    }

    /** Applies material colors. */
    public static void applyMaterialColor(@NonNull Context context,
            @NonNull LottieAnimationView lottieAnimationView) {
        if (!SettingsThemeHelper.isExpressiveTheme(context)) {
            return;
        }

        for (String key : MATERIAL_COLOR_MAP.keySet()) {
            final int color = context.getColor(MATERIAL_COLOR_MAP.get(key));
            lottieAnimationView.addValueCallback(
                    new KeyPath("**", key, "**"),
                    LottieProperty.COLOR_FILTER,
                    frameInfo -> new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP));
        }
    }
}
