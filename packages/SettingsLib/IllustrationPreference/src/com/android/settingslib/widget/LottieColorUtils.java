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

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Util class which dynamically changes the color of tags in a lottie json file between Dark Theme
 * (DT) and Light Theme (LT). This class assumes the json file is for Dark Theme.
 */
public class LottieColorUtils {
    private static final Map<String, Integer> DARK_TO_LIGHT_THEME_COLOR_MAP;

    static {
        HashMap<String, Integer> map = new HashMap<>();
        map.put(
                ".grey600",
                R.color.settingslib_color_grey300);
        map.put(
                ".grey800",
                R.color.settingslib_color_grey200);
        map.put(
                ".grey900",
                R.color.settingslib_color_grey50);
        map.put(
                ".red400",
                R.color.settingslib_color_red600);
        map.put(
                ".black",
                android.R.color.white);
        map.put(
                ".blue400",
                R.color.settingslib_color_blue600);
        map.put(
                ".green400",
                R.color.settingslib_color_green600);
        DARK_TO_LIGHT_THEME_COLOR_MAP = Collections.unmodifiableMap(map);
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
}
