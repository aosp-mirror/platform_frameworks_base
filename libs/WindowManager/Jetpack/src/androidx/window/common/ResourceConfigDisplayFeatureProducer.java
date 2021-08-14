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

package androidx.window.common;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.window.util.BaseDataProducer;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of {@link androidx.window.util.DataProducer} that produces
 * {@link CommonDisplayFeature} parsed from a string stored in the resources config at
 * {@link R.string#config_display_features}.
 */
public final class ResourceConfigDisplayFeatureProducer extends
        BaseDataProducer<List<DisplayFeature>> {
    private static final boolean DEBUG = false;
    private static final String TAG = "ResourceConfigDisplayFeatureProducer";

    private final Context mContext;

    public ResourceConfigDisplayFeatureProducer(@NonNull Context context) {
        mContext = context;
    }

    @Override
    @Nullable
    public Optional<List<DisplayFeature>> getData() {
        String displayFeaturesString = mContext.getResources().getString(
                R.string.config_display_features);
        if (TextUtils.isEmpty(displayFeaturesString)) {
            return Optional.empty();
        }

        List<DisplayFeature> features = new ArrayList<>();
        String[] featureStrings =  displayFeaturesString.split(";");
        for (String featureString : featureStrings) {
            CommonDisplayFeature feature;
            try {
                feature = CommonDisplayFeature.parseFromString(featureString);
            } catch (IllegalArgumentException e) {
                if (DEBUG) {
                    Log.w(TAG, "Failed to parse display feature: " + featureString, e);
                }
                continue;
            }
            features.add(feature);
        }
        return Optional.of(features);
    }
}
