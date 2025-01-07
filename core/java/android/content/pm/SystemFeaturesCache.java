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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;
import java.util.Collection;

/**
 * A simple cache for SDK-defined system feature versions.
 *
 * The dense representation minimizes any per-process memory impact (<1KB). The tradeoff is that
 * custom, non-SDK defined features are not captured by the cache, for which we can rely on the
 * usual IPC cache for related queries.
 *
 * @hide
 */
public final class SystemFeaturesCache implements Parcelable {

    // Sentinel value used for SDK-declared features that are unavailable on the current device.
    private static final int UNAVAILABLE_FEATURE_VERSION = Integer.MIN_VALUE;

    // An array of versions for SDK-defined features, from [0, PackageManager.SDK_FEATURE_COUNT).
    @NonNull
    private final int[] mSdkFeatureVersions;

    /**
     * Populates the cache from the set of all available {@link FeatureInfo} definitions.
     *
     * System features declared in {@link PackageManager} will be entered into the cache based on
     * availability in this feature set. Other custom system features will be ignored.
     */
    public SystemFeaturesCache(@NonNull ArrayMap<String, FeatureInfo> availableFeatures) {
        this(availableFeatures.values());
    }

    @VisibleForTesting
    public SystemFeaturesCache(@NonNull Collection<FeatureInfo> availableFeatures) {
        // First set all SDK-defined features as unavailable.
        mSdkFeatureVersions = new int[PackageManager.SDK_FEATURE_COUNT];
        Arrays.fill(mSdkFeatureVersions, UNAVAILABLE_FEATURE_VERSION);

        // Then populate SDK-defined feature versions from the full set of runtime features.
        for (FeatureInfo fi : availableFeatures) {
            int sdkFeatureIndex = PackageManager.maybeGetSdkFeatureIndex(fi.name);
            if (sdkFeatureIndex >= 0) {
                mSdkFeatureVersions[sdkFeatureIndex] = fi.version;
            }
        }
    }

    /** Only used by @{code CREATOR.createFromParcel(...)} */
    private SystemFeaturesCache(@NonNull Parcel parcel) {
        final int[] featureVersions = parcel.createIntArray();
        if (featureVersions == null) {
            throw new IllegalArgumentException(
                    "Parceled SDK feature versions should never be null");
        }
        if (featureVersions.length != PackageManager.SDK_FEATURE_COUNT) {
            throw new IllegalArgumentException(
                    String.format(
                            "Unexpected cached SDK feature count: %d (expected %d)",
                            featureVersions.length, PackageManager.SDK_FEATURE_COUNT));
        }
        mSdkFeatureVersions = featureVersions;
    }

    /**
     * @return Whether the given feature is available (for SDK-defined features), otherwise null.
     */
    public Boolean maybeHasFeature(@NonNull String featureName, int version) {
        // Features defined outside of the SDK aren't cached.
        int sdkFeatureIndex = PackageManager.maybeGetSdkFeatureIndex(featureName);
        if (sdkFeatureIndex < 0) {
            return null;
        }

        // As feature versions can in theory collide with our sentinel value, in the (extremely)
        // unlikely event that the queried version matches the sentinel value, we can't distinguish
        // between an unavailable feature and a feature with the defined sentinel value.
        if (version == UNAVAILABLE_FEATURE_VERSION
                && mSdkFeatureVersions[sdkFeatureIndex] == UNAVAILABLE_FEATURE_VERSION) {
            return null;
        }

        return mSdkFeatureVersions[sdkFeatureIndex] >= version;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeIntArray(mSdkFeatureVersions);
    }

    @NonNull
    public static final Parcelable.Creator<SystemFeaturesCache> CREATOR =
            new Parcelable.Creator<SystemFeaturesCache>() {

                @Override
                public SystemFeaturesCache createFromParcel(Parcel parcel) {
                    return new SystemFeaturesCache(parcel);
                }

                @Override
                public SystemFeaturesCache[] newArray(int size) {
                    return new SystemFeaturesCache[size];
                }
            };
}
