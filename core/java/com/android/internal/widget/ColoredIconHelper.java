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

package com.android.internal.widget;

import android.annotation.ColorInt;
import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;

import com.android.internal.util.ContrastColorUtil;

/** Helpers for colored icons */
final class ColoredIconHelper {

    @ColorInt
    static final int COLOR_INVALID = Notification.COLOR_INVALID;

    private ColoredIconHelper() {
    }

    /**
     * Apply a gray tint or the original color to a drawable, accounting for the night mode in
     * selecting the gray.
     */
    static void applyGrayTint(Context ctx, Drawable drawable, boolean apply, int originalColor) {
        if (originalColor == COLOR_INVALID) {
            return;
        }
        if (apply) {
            // lets gray it out
            Configuration config = ctx.getResources().getConfiguration();
            boolean inNightMode = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int grey = ContrastColorUtil.resolveColor(ctx, Notification.COLOR_DEFAULT, inNightMode);
            drawable.mutate().setColorFilter(grey, PorterDuff.Mode.SRC_ATOP);
        } else {
            // lets reset it
            drawable.mutate().setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP);
        }
    }
}
