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

package com.android.settingslib.activityembedding;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import com.android.settingslib.utils.BuildCompatUtils;

/**
 * An util class collecting all common methods for the embedding activity features.
 */
public class ActivityEmbeddingUtils {
    private static final String ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY =
            "android.settings.SETTINGS_EMBED_DEEP_LINK_ACTIVITY";
    private static final String PACKAGE_NAME_SETTINGS = "com.android.settings";

    /**
     * Whether to support embedding activity feature.
     */
    public static boolean isEmbeddingActivityEnabled(Context context) {
        if (BuildCompatUtils.isAtLeastS()) {
            final Intent intent = new Intent(ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY);
            intent.setPackage(PACKAGE_NAME_SETTINGS);
            final ResolveInfo resolveInfo =
                    context.getPackageManager().resolveActivity(intent, 0 /* flags */);
            return resolveInfo != null
                    && resolveInfo.activityInfo != null
                    && resolveInfo.activityInfo.enabled;
        }
        return false;
    }

    private ActivityEmbeddingUtils() {
    }
}
