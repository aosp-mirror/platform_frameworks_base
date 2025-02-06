/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.content.pm;

import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.util.ArrayMap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SystemFeaturesCacheTest {

    private SystemFeaturesCache mCache;

    @Test
    public void testNoFeatures() throws Exception {
        SystemFeaturesCache cache = new SystemFeaturesCache(new ArrayMap<String, FeatureInfo>());
        assertThat(cache.maybeHasFeature("", 0)).isNull();
        assertThat(cache.maybeHasFeature(FEATURE_WATCH, 0)).isFalse();
        assertThat(cache.maybeHasFeature(FEATURE_PICTURE_IN_PICTURE, 0)).isFalse();
        assertThat(cache.maybeHasFeature("com.missing.feature", 0)).isNull();
    }

    @Test
    public void testNonSdkFeature() throws Exception {
        ArrayMap<String, FeatureInfo> features = new ArrayMap<>();
        features.put("custom.feature", createFeature("custom.feature", 0));
        SystemFeaturesCache cache = new SystemFeaturesCache(features);

        assertThat(cache.maybeHasFeature("custom.feature", 0)).isNull();
    }

    @Test
    public void testSdkFeature() throws Exception {
        ArrayMap<String, FeatureInfo> features = new ArrayMap<>();
        features.put(FEATURE_WATCH, createFeature(FEATURE_WATCH, 0));
        SystemFeaturesCache cache = new SystemFeaturesCache(features);

        assertThat(cache.maybeHasFeature(FEATURE_WATCH, 0)).isTrue();
        assertThat(cache.maybeHasFeature(FEATURE_WATCH, -1)).isTrue();
        assertThat(cache.maybeHasFeature(FEATURE_WATCH, 1)).isFalse();
        assertThat(cache.maybeHasFeature(FEATURE_WATCH, Integer.MIN_VALUE)).isTrue();
        assertThat(cache.maybeHasFeature(FEATURE_WATCH, Integer.MAX_VALUE)).isFalse();

        // Other SDK-declared features should be reported as unavailable.
        assertThat(cache.maybeHasFeature(FEATURE_PICTURE_IN_PICTURE, 0)).isFalse();
    }

    @Test
    public void testSdkFeatureHasMinVersion() throws Exception {
        ArrayMap<String, FeatureInfo> features = new ArrayMap<>();
        features.put(FEATURE_WATCH, createFeature(FEATURE_WATCH, Integer.MIN_VALUE));
        SystemFeaturesCache cache = new SystemFeaturesCache(features);

        assertThat(cache.maybeHasFeature(FEATURE_WATCH, 0)).isFalse();

        // If both the query and the feature version itself happen to use MIN_VALUE, we can't
        // reliably indicate availability, so it should report an indeterminate result.
        assertThat(cache.maybeHasFeature(FEATURE_WATCH, Integer.MIN_VALUE)).isNull();
    }

    @Test
    public void testParcel() throws Exception {
        ArrayMap<String, FeatureInfo> features = new ArrayMap<>();
        features.put(FEATURE_WATCH, createFeature(FEATURE_WATCH, 0));
        SystemFeaturesCache cache = new SystemFeaturesCache(features);

        Parcel parcel = Parcel.obtain();
        SystemFeaturesCache parceledCache;
        try {
            parcel.writeParcelable(cache, 0);
            parcel.setDataPosition(0);
            parceledCache = parcel.readParcelable(getClass().getClassLoader());
        } finally {
            parcel.recycle();
        }

        assertThat(parceledCache.maybeHasFeature(FEATURE_WATCH, 0))
                .isEqualTo(cache.maybeHasFeature(FEATURE_WATCH, 0));
        assertThat(parceledCache.maybeHasFeature(FEATURE_PICTURE_IN_PICTURE, 0))
                .isEqualTo(cache.maybeHasFeature(FEATURE_PICTURE_IN_PICTURE, 0));
        assertThat(parceledCache.maybeHasFeature("custom.feature", 0))
                .isEqualTo(cache.maybeHasFeature("custom.feature", 0));
    }

    private static FeatureInfo createFeature(String name, int version) {
        FeatureInfo fi = new FeatureInfo();
        fi.name = name;
        fi.version = version;
        return fi;
    }
}
