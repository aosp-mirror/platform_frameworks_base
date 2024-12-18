/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.extensions.layout;

import androidx.window.common.layout.DisplayFoldFeatureCommon;

/**
 * Util functions for working with {@link androidx.window.extensions.layout.DisplayFoldFeature}.
 */
public final class DisplayFoldFeatureUtil {

    private DisplayFoldFeatureUtil() {}

    /**
     * Returns a {@link DisplayFoldFeature} that matches the given {@link DisplayFoldFeatureCommon}.
     */
    public static DisplayFoldFeature translate(DisplayFoldFeatureCommon foldFeatureCommon) {
        final DisplayFoldFeature.Builder builder =
                new DisplayFoldFeature.Builder(foldFeatureCommon.getType());
        for (int property: foldFeatureCommon.getProperties()) {
            builder.addProperty(property);
        }
        return builder.build();
    }
}
