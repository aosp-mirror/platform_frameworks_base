/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.Pair;

import com.android.settingslib.color.R;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.value.LottieFrameInfo;
import com.airbnb.lottie.value.SimpleLottieValueCallback;

import java.util.HashMap;

/**
 * ColorUtils is a util class which help the lottie illustration
 * changes the color of tags in the json file.
 */

public class ColorUtils {

    private static HashMap<String, Integer> sSysColors;

    private static HashMap<String, Pair<Integer, Integer>> sFixedColors;
    static {
        sFixedColors = new HashMap<>();
        sFixedColors.put(".blue600", new Pair<Integer, Integer>(
                R.color.settingslib_color_blue600, R.color.settingslib_color_blue400));
        sFixedColors.put(".green600", new Pair<Integer, Integer>(
                R.color.settingslib_color_green600, R.color.settingslib_color_green400));
        sFixedColors.put(".red600", new Pair<Integer, Integer>(
                R.color.settingslib_color_red600, R.color.settingslib_color_red400));
        sFixedColors.put(".yellow600", new Pair<Integer, Integer>(
                R.color.settingslib_color_yellow600, R.color.settingslib_color_yellow400));
        sFixedColors.put(".blue400", new Pair<Integer, Integer>(
                R.color.settingslib_color_blue400, R.color.settingslib_color_blue100));
        sFixedColors.put(".green400", new Pair<Integer, Integer>(
                R.color.settingslib_color_green400, R.color.settingslib_color_green100));
        sFixedColors.put(".red400", new Pair<Integer, Integer>(
                R.color.settingslib_color_red400, R.color.settingslib_color_red100));
        sFixedColors.put(".yellow400", new Pair<Integer, Integer>(
                R.color.settingslib_color_yellow400, R.color.settingslib_color_yellow100));
        sFixedColors.put(".blue300", new Pair<Integer, Integer>(
                R.color.settingslib_color_blue300, R.color.settingslib_color_blue50));
        sFixedColors.put(".blue50", new Pair<Integer, Integer>(
                R.color.settingslib_color_blue50, R.color.settingslib_color_grey900));
        sFixedColors.put(".green50", new Pair<Integer, Integer>(
                R.color.settingslib_color_green50, R.color.settingslib_color_grey900));
        sFixedColors.put(".red50", new Pair<Integer, Integer>(
                R.color.settingslib_color_red50, R.color.settingslib_color_grey900));
        sFixedColors.put(".yellow50", new Pair<Integer, Integer>(
                R.color.settingslib_color_yellow50, R.color.settingslib_color_grey900));
        // Secondary colors
        sFixedColors.put(".orange600", new Pair<Integer, Integer>(
                R.color.settingslib_color_orange600, R.color.settingslib_color_orange300));
        sFixedColors.put(".pink600", new Pair<Integer, Integer>(
                R.color.settingslib_color_pink600, R.color.settingslib_color_pink300));
        sFixedColors.put(".purple600", new Pair<Integer, Integer>(
                R.color.settingslib_color_purple600, R.color.settingslib_color_purple300));
        sFixedColors.put(".cyan600", new Pair<Integer, Integer>(
                R.color.settingslib_color_cyan600, R.color.settingslib_color_cyan300));
        sFixedColors.put(".orange400", new Pair<Integer, Integer>(
                R.color.settingslib_color_orange400, R.color.settingslib_color_orange100));
        sFixedColors.put(".pink400", new Pair<Integer, Integer>(
                R.color.settingslib_color_pink400, R.color.settingslib_color_pink100));
        sFixedColors.put(".purple400", new Pair<Integer, Integer>(
                R.color.settingslib_color_purple400, R.color.settingslib_color_purple100));
        sFixedColors.put(".cyan400", new Pair<Integer, Integer>(
                R.color.settingslib_color_cyan400, R.color.settingslib_color_cyan100));
        sFixedColors.put(".gery400", new Pair<Integer, Integer>(
                R.color.settingslib_color_grey400, R.color.settingslib_color_grey700));
        sFixedColors.put(".gery300", new Pair<Integer, Integer>(
                R.color.settingslib_color_grey300, R.color.settingslib_color_grey600));
        sFixedColors.put(".gery200", new Pair<Integer, Integer>(
                R.color.settingslib_color_grey200, R.color.settingslib_color_grey800));
    }

    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Apply the color of tags to the animation.
     */
    public static void applyDynamicColors(Context context, LottieAnimationView animationView) {
        for (String key : sFixedColors.keySet()) {
            final Pair<Integer, Integer> fixedColorPair = sFixedColors.get(key);
            final int color = isDarkMode(context) ? fixedColorPair.second : fixedColorPair.first;
            animationView.addValueCallback(
                    new KeyPath("**", key, "**"),
                    LottieProperty.COLOR_FILTER,
                    new SimpleLottieValueCallback<ColorFilter>() {
                        @Override
                        public ColorFilter getValue(LottieFrameInfo<ColorFilter> frameInfo) {
                            return new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
                        }
                    }
            );
        }
    }
}
