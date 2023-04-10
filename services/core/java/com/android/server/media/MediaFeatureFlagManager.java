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

    @StringDef(prefix = "FEATURE_", value = {
            FEATURE_AUDIO_STRATEGIES_IS_USING_LEGACY_CONTROLLER
    })
    @Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
    @Retention(RetentionPolicy.SOURCE)
    /* package */ @interface MediaFeatureFlag {}

    /**
     * Whether to use old legacy implementation of BluetoothRouteController or new
     * 'Audio Strategies'-aware controller.
     */
    /* package */ static final @MediaFeatureFlag String
            FEATURE_AUDIO_STRATEGIES_IS_USING_LEGACY_CONTROLLER =
            "BluetoothRouteController__enable_legacy_bluetooth_routes_controller";

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
}
