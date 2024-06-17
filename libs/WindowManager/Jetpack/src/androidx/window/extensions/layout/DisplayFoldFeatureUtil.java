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

import androidx.window.common.CommonFoldingFeature;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;

import java.util.ArrayList;
import java.util.List;

/**
 * Util functions for working with {@link androidx.window.extensions.layout.DisplayFoldFeature}.
 */
public class DisplayFoldFeatureUtil {

    private DisplayFoldFeatureUtil() {}

    private static DisplayFoldFeature create(CommonFoldingFeature foldingFeature,
            boolean isHalfOpenedSupported) {
        final int foldType;
        if (foldingFeature.getType() == CommonFoldingFeature.COMMON_TYPE_HINGE) {
            foldType = DisplayFoldFeature.TYPE_HINGE;
        } else {
            foldType = DisplayFoldFeature.TYPE_SCREEN_FOLD_IN;
        }
        DisplayFoldFeature.Builder featureBuilder = new DisplayFoldFeature.Builder(foldType);

        if (isHalfOpenedSupported) {
            featureBuilder.addProperty(DisplayFoldFeature.FOLD_PROPERTY_SUPPORTS_HALF_OPENED);
        }
        return featureBuilder.build();
    }

    /**
     * Returns the list of supported {@link DisplayFeature} calculated from the
     * {@link DeviceStateManagerFoldingFeatureProducer}.
     */
    public static List<DisplayFoldFeature> extractDisplayFoldFeatures(
            DeviceStateManagerFoldingFeatureProducer producer) {
        List<DisplayFoldFeature> foldFeatures = new ArrayList<>();
        List<CommonFoldingFeature> folds = producer.getFoldsWithUnknownState();

        final boolean isHalfOpenedSupported = producer.isHalfOpenedSupported();
        for (CommonFoldingFeature fold : folds) {
            foldFeatures.add(DisplayFoldFeatureUtil.create(fold, isHalfOpenedSupported));
        }
        return foldFeatures;
    }
}
