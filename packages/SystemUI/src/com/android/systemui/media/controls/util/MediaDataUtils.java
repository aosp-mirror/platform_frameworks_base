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
}
