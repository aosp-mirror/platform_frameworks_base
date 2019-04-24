/*
 * Copyright 2019 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Describes the state of a media router provider and the routes that it publishes.
 * @hide
 */
public final class MediaRoute2ProviderInfo implements Parcelable {
    public static final Parcelable.Creator<MediaRoute2ProviderInfo> CREATOR =
            new Parcelable.Creator<MediaRoute2ProviderInfo>() {
        @Override
        public MediaRoute2ProviderInfo createFromParcel(Parcel in) {
            return new MediaRoute2ProviderInfo(in);
        }
        @Override
        public MediaRoute2ProviderInfo[] newArray(int size) {
            return new MediaRoute2ProviderInfo[size];
        }
    };

    @NonNull
    private final List<MediaRoute2Info> mRoutes;

    MediaRoute2ProviderInfo(@Nullable List<MediaRoute2Info> routes) {
        mRoutes = (routes == null) ? Collections.emptyList() : routes;
    }

    MediaRoute2ProviderInfo(@NonNull Parcel src) {
        mRoutes = src.createTypedArrayList(MediaRoute2Info.CREATOR);
    }

    /**
     * Gets the unmodifiable list of all routes that this provider has published.
     */
    @NonNull
    public List<MediaRoute2Info> getRoutes() {
        return Collections.unmodifiableList(mRoutes);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(mRoutes);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("MediaRouteProviderInfo { ")
                .append("routes=").append(Arrays.toString(getRoutes().toArray()))
                .append(" }");
        return result.toString();
    }

    /**
     * Builder for {@link MediaRoute2ProviderInfo media route provider info}.
     */
    public static final class Builder {
        @NonNull
        private final List<MediaRoute2Info> mRoutes;

        public Builder() {
            mRoutes = new ArrayList<>();
        }

        public Builder(@NonNull MediaRoute2ProviderInfo descriptor) {
            if (descriptor == null) {
                throw new IllegalArgumentException("descriptor must not be null");
            }
            mRoutes = new ArrayList<>(descriptor.mRoutes);
        }

        /**
         * Adds a route to the provider
         */
        public Builder addRoute(@NonNull MediaRoute2Info route) {
            if (route == null) {
                throw new IllegalArgumentException("route must not be null");
            }

            if (mRoutes.contains(route)) {
                throw new IllegalArgumentException("route descriptor already added");
            }
            mRoutes.add(route);
            return this;
        }

        /**
         * Adds a list of routes to the provider
         */
        public Builder addRoutes(@NonNull Collection<MediaRoute2Info> routes) {
            if (routes == null) {
                throw new IllegalArgumentException("routes must not be null");
            }

            if (!routes.isEmpty()) {
                for (MediaRoute2Info route : routes) {
                    addRoute(route);
                }
            }
            return this;
        }

        /**
         * Builds {@link MediaRoute2ProviderInfo media route provider info}.
         */
        @NonNull
        public MediaRoute2ProviderInfo build() {
            return new MediaRoute2ProviderInfo(mRoutes);
        }
    }
}
