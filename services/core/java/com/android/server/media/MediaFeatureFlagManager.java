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

package com.android.server.media;

import android.annotation.StringDef;
import android.app.ActivityThread;
import android.app.Application;
import android.provider.DeviceConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/* package */ class MediaFeatureFlagManager {

    /**
     * Namespace for media better together features.
     */
    private static final String NAMESPACE_MEDIA_BETTER_TOGETHER = "media_better_together";

    @StringDef(
            prefix = "FEATURE_",
            value = {
                FEATURE_SCANNING_MINIMUM_PACKAGE_IMPORTANCE
            })
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @Retention(RetentionPolicy.SOURCE)
    /* package */ @interface MediaFeatureFlag {}

    /**
     * Whether to use IMPORTANCE_FOREGROUND (i.e. 100) or IMPORTANCE_FOREGROUND_SERVICE (i.e. 125)
     * as the minimum package importance for scanning.
     */
    /* package */ static final @MediaFeatureFlag String
            FEATURE_SCANNING_MINIMUM_PACKAGE_IMPORTANCE = "scanning_package_minimum_importance";

    private static final MediaFeatureFlagManager sInstance = new MediaFeatureFlagManager();

    private MediaFeatureFlagManager() {
        // Empty to prevent instantiation.
    }

    /* package */ static MediaFeatureFlagManager getInstance() {
        return sInstance;
    }

    /**
     * Returns a boolean value from {@link DeviceConfig} from the system_time namespace, or
     * {@code defaultValue} if there is no explicit value set.
     */
    public boolean getBoolean(@MediaFeatureFlag String key, boolean defaultValue) {
        return DeviceConfig.getBoolean(NAMESPACE_MEDIA_BETTER_TOGETHER, key, defaultValue);
    }

    /**
     * Returns an int value from {@link DeviceConfig} from the system_time namespace, or {@code
     * defaultValue} if there is no explicit value set.
     */
    public int getInt(@MediaFeatureFlag String key, int defaultValue) {
        return DeviceConfig.getInt(NAMESPACE_MEDIA_BETTER_TOGETHER, key, defaultValue);
    }

    /**
     * Adds a listener to react for changes in media feature flags values. Future calls to this
     * method with the same listener will replace the old namespace and executor.
     *
     * @param onPropertiesChangedListener The listener to add.
     */
    public void addOnPropertiesChangedListener(
            DeviceConfig.OnPropertiesChangedListener onPropertiesChangedListener) {
        Application currentApplication = ActivityThread.currentApplication();
        if (currentApplication != null) {
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_MEDIA_BETTER_TOGETHER,
                    currentApplication.getMainExecutor(),
                    onPropertiesChangedListener);
        }
    }
}
