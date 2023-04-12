/**
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

package com.android.systemui.media.controls.util;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;

import androidx.core.math.MathUtils;
import androidx.media.utils.MediaConstants;

/**
 * Utility class with common methods for media controls
 */
public class MediaDataUtils {

    /**
     * Get the application label for a given package
     * @param context the context to use
     * @param packageName Package to check
     * @param unknownName Fallback string if application is not found
     * @return The label or fallback string
     */
    public static String getAppLabel(Context context, String packageName, String unknownName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        final PackageManager packageManager = context.getPackageManager();
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            applicationInfo = null;
        }
        final String applicationName =
                (String) (applicationInfo != null
                        ? packageManager.getApplicationLabel(applicationInfo)
                        : unknownName);
        return applicationName;
    }

    /**
     * Check the bundle for extras indicating the progress percentage
     *
     * @param extras
     * @return the progress value between 0-1 inclusive if prsent, otherwise null
     */
    public static Double getDescriptionProgress(@Nullable Bundle extras) {
        if (extras == null
                || !extras.containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS)) {
            return null;
        }

        int status = extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS);
        switch (status) {
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED:
                return 0.0;
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED:
                return 1.0;
            case MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED: {
                if (extras
                        .containsKey(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE)) {
                    double percent = extras
                            .getDouble(MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE);
                    return MathUtils.clamp(percent, 0.0, 1.0);
                } else {
                    return 0.5;
                }
            }
        }
        return null;
    }

    /**
     * Calculate a scale factor that will allow the input to fill the target size.
     *
     * @param input width, height of the input view
     * @param target width, height of the target view
     * @return the scale factor; 0 if any given dimension is 0
     */
    public static float getScaleFactor(Pair<Integer, Integer> input,
            Pair<Integer, Integer> target) {
        float width = (float) input.first;
        float height = (float) input.second;

        float targetWidth = (float) target.first;
        float targetHeight = (float) target.second;

        if (width == 0 || height == 0 || targetWidth == 0 || targetHeight == 0) {
            return 0f;
        }

        if ((width / height) > (targetWidth / targetHeight)) {
            // Input is wider than target view, scale to match height
            return targetHeight / height;
        } else {
            // Input is taller than target view, scale to match width
            return targetWidth / width;
        }
    }
}
