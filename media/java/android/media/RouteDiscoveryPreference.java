/*
 * Copyright 2020 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A media route discovery preference describing the features of routes that media router
 * would like to discover and whether to perform active scanning.
 * <p>
 * When {@link MediaRouter2} instances set discovery preferences by calling
 * {@link MediaRouter2#registerRouteCallback}, they are merged into a single discovery preference
 * and it is delivered to call {@link MediaRoute2ProviderService#onDiscoveryPreferenceChanged}.
 * </p><p>
 * According to the given discovery preference, {@link MediaRoute2ProviderService} discovers
 * routes and publishes them.
 * </p>
 *
 * @see MediaRouter2#registerRouteCallback
 */
public final class RouteDiscoveryPreference implements Parcelable {
    @NonNull
    public static final Creator<RouteDiscoveryPreference> CREATOR =
            new Creator<RouteDiscoveryPreference>() {
                @Override
                public RouteDiscoveryPreference createFromParcel(Parcel in) {
                    return new RouteDiscoveryPreference(in);
                }

                @Override
                public RouteDiscoveryPreference[] newArray(int size) {
                    return new RouteDiscoveryPreference[size];
                }
            };

    @NonNull
    private final List<String> mPreferredFeatures;
    private final boolean mShouldPerformActiveScan;
    @Nullable
    private final Bundle mExtras;

    /**
     * An empty discovery preference
     * @hide
     */
    public static final RouteDiscoveryPreference EMPTY =
            new Builder(Collections.emptyList(), false).build();

    RouteDiscoveryPreference(@NonNull Builder builder) {
        mPreferredFeatures = builder.mPreferredFeatures;
        mShouldPerformActiveScan = builder.mActiveScan;
        mExtras = builder.mExtras;
    }

    RouteDiscoveryPreference(@NonNull Parcel in) {
        mPreferredFeatures = in.createStringArrayList();
        mShouldPerformActiveScan = in.readBoolean();
        mExtras = in.readBundle();
    }

    /**
     * Gets the features of routes that media router would like to discover.
     * <p>
     * Routes that have at least one of the features will be discovered.
     * They may include predefined features such as
     * {@link MediaRoute2Info#FEATURE_LIVE_AUDIO}, {@link MediaRoute2Info#FEATURE_LIVE_VIDEO},
     * or {@link MediaRoute2Info#FEATURE_REMOTE_PLAYBACK} or custom features defined by a provider.
     * </p>
     */
    @NonNull
    public List<String> getPreferredFeatures() {
        return mPreferredFeatures;
    }

    /**
     * Gets whether active scanning should be performed.
     * <p>
     * If any of discovery preferences sets this as {@code true}, active scanning will
     * be performed regardless of other discovery preferences.
     * </p>
     */
    public boolean shouldPerformActiveScan() {
        return mShouldPerformActiveScan;
    }

    /**
     * @hide
     */
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStringList(mPreferredFeatures);
        dest.writeBoolean(mShouldPerformActiveScan);
        dest.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("RouteDiscoveryRequest{ ")
                .append("preferredFeatures={")
                .append(String.join(", ", mPreferredFeatures))
                .append("}")
                .append(", activeScan=")
                .append(mShouldPerformActiveScan)
                .append(" }");

        return result.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RouteDiscoveryPreference)) {
            return false;
        }
        RouteDiscoveryPreference other = (RouteDiscoveryPreference) o;
        //TODO: Make this order-free
        return Objects.equals(mPreferredFeatures, other.mPreferredFeatures)
                && mShouldPerformActiveScan == other.mShouldPerformActiveScan;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPreferredFeatures, mShouldPerformActiveScan);
    }

    /**
     * Builder for {@link RouteDiscoveryPreference}.
     */
    public static final class Builder {
        List<String> mPreferredFeatures;
        boolean mActiveScan;
        Bundle mExtras;

        public Builder(@NonNull List<String> preferredFeatures, boolean activeScan) {
            Objects.requireNonNull(preferredFeatures, "preferredFeatures must not be null");
            mPreferredFeatures = preferredFeatures.stream().filter(str -> !TextUtils.isEmpty(str))
                    .collect(Collectors.toList());
            mActiveScan = activeScan;
        }

        public Builder(@NonNull RouteDiscoveryPreference preference) {
            Objects.requireNonNull(preference, "preference must not be null");

            mPreferredFeatures = preference.getPreferredFeatures();
            mActiveScan = preference.shouldPerformActiveScan();
            mExtras = preference.getExtras();
        }

        /**
         * A constructor to combine all of the preferences into a single preference.
         * It ignores extras of preferences.
         *
         * @hide
         */
        public Builder(@NonNull Collection<RouteDiscoveryPreference> preferences) {
            Objects.requireNonNull(preferences, "preferences must not be null");

            Set<String> routeFeatureSet = new HashSet<>();
            mActiveScan = false;
            for (RouteDiscoveryPreference preference : preferences) {
                routeFeatureSet.addAll(preference.mPreferredFeatures);
                mActiveScan |= preference.mShouldPerformActiveScan;
            }
            mPreferredFeatures = new ArrayList<>(routeFeatureSet);
        }

        /**
         * Sets preferred route features to discover.
         * @param preferredFeatures features of routes that media router would like to discover.
         *                          May include predefined features
         *                          such as {@link MediaRoute2Info#FEATURE_LIVE_AUDIO},
         *                          {@link MediaRoute2Info#FEATURE_LIVE_VIDEO},
         *                          or {@link MediaRoute2Info#FEATURE_REMOTE_PLAYBACK}
         *                          or custom features defined by a provider.
         */
        @NonNull
        public Builder setPreferredFeatures(@NonNull List<String> preferredFeatures) {
            Objects.requireNonNull(preferredFeatures, "preferredFeatures must not be null");
            mPreferredFeatures = preferredFeatures.stream().filter(str -> !TextUtils.isEmpty(str))
                    .collect(Collectors.toList());
            return this;
        }

        /**
         * Sets if active scanning should be performed.
         * <p>
         * Since active scanning uses more system resources, set this as {@code true} only
         * when it's necessary.
         * </p>
         */
        @NonNull
        public Builder setShouldPerformActiveScan(boolean activeScan) {
            mActiveScan = activeScan;
            return this;
        }

        /**
         * Sets the extras of the route.
         * @hide
         */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link RouteDiscoveryPreference}.
         */
        @NonNull
        public RouteDiscoveryPreference build() {
            return new RouteDiscoveryPreference(this);
        }
    }
}
