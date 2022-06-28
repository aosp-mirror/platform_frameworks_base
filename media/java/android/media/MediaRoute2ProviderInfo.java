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
import android.text.TextUtils;
import android.util.ArrayMap;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Describes the state of a media router provider and the routes that it publishes.
 * @hide
 */
public final class MediaRoute2ProviderInfo implements Parcelable {
    @NonNull
    public static final Creator<MediaRoute2ProviderInfo> CREATOR =
            new Creator<MediaRoute2ProviderInfo>() {
        @Override
        public MediaRoute2ProviderInfo createFromParcel(Parcel in) {
            return new MediaRoute2ProviderInfo(in);
        }
        @Override
        public MediaRoute2ProviderInfo[] newArray(int size) {
            return new MediaRoute2ProviderInfo[size];
        }
    };

    @Nullable
    final String mUniqueId;
    @NonNull
    final ArrayMap<String, MediaRoute2Info> mRoutes;

    MediaRoute2ProviderInfo(@NonNull Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null.");

        mUniqueId = builder.mUniqueId;
        mRoutes = builder.mRoutes;
    }

    MediaRoute2ProviderInfo(@NonNull Parcel src) {
        mUniqueId = src.readString();
        ArrayMap<String, MediaRoute2Info> routes = src.createTypedArrayMap(MediaRoute2Info.CREATOR);
        mRoutes = (routes == null) ? ArrayMap.EMPTY : routes;
    }

    /**
     * Returns true if the information of the provider and all of it's routes have all
     * of the required fields.
     * @hide
     */
    public boolean isValid() {
        if (mUniqueId == null) {
            return false;
        }
        final int count = mRoutes.size();
        for (int i = 0; i < count; i++) {
            MediaRoute2Info route = mRoutes.valueAt(i);
            if (route == null || !route.isValid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @hide
     */
    @Nullable
    public String getUniqueId() {
        return mUniqueId;
    }

    /**
     * Gets the route for the given route id or null if no matching route exists.
     * Please note that id should be original id.
     *
     * @see MediaRoute2Info#getOriginalId()
     */
    @Nullable
    public MediaRoute2Info getRoute(@NonNull String routeId) {
        return mRoutes.get(Objects.requireNonNull(routeId, "routeId must not be null"));
    }

    /**
     * Gets the unmodifiable list of all routes that this provider has published.
     */
    @NonNull
    public Collection<MediaRoute2Info> getRoutes() {
        return mRoutes.values();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mUniqueId);
        dest.writeTypedArrayMap(mRoutes, flags);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("MediaRouteProviderInfo { ")
                .append("uniqueId=").append(mUniqueId)
                .append(", routes=").append(Arrays.toString(getRoutes().toArray()))
                .append(" }");
        return result.toString();
    }

    /**
     * Builder for {@link MediaRoute2ProviderInfo media route provider info}.
     */
    public static final class Builder {
        @NonNull
        final ArrayMap<String, MediaRoute2Info> mRoutes;
        String mUniqueId;

        public Builder() {
            mRoutes = new ArrayMap<>();
        }

        public Builder(@NonNull MediaRoute2ProviderInfo descriptor) {
            Objects.requireNonNull(descriptor, "descriptor must not be null");

            mUniqueId = descriptor.mUniqueId;
            mRoutes = new ArrayMap<>(descriptor.mRoutes);
        }

        /**
         * Sets the package name and unique id of the provider info.
         * <p>
         * The unique id is automatically set by
         * {@link com.android.server.media.MediaRouterService} and used to identify providers.
         * The id set by {@link MediaRoute2ProviderService} will be ignored.
         * </p>
         * @hide
         */
        @NonNull
        public Builder setUniqueId(@Nullable String packageName, @Nullable String uniqueId) {
            if (TextUtils.equals(mUniqueId, uniqueId)) {
                return this;
            }
            mUniqueId = uniqueId;

            final ArrayMap<String, MediaRoute2Info> newRoutes = new ArrayMap<>();
            for (Map.Entry<String, MediaRoute2Info> entry : mRoutes.entrySet()) {
                MediaRoute2Info routeWithProviderId = new MediaRoute2Info.Builder(entry.getValue())
                        .setPackageName(packageName)
                        .setProviderId(mUniqueId)
                        .build();
                newRoutes.put(routeWithProviderId.getOriginalId(), routeWithProviderId);
            }

            mRoutes.clear();
            mRoutes.putAll(newRoutes);
            return this;
        }

        /**
         * Sets whether the provider provides system routes or not
         */
        @NonNull
        public Builder setSystemRouteProvider(boolean isSystem) {
            int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                MediaRoute2Info route = mRoutes.valueAt(i);
                if (route.isSystemRoute() != isSystem) {
                    mRoutes.setValueAt(i, new MediaRoute2Info.Builder(route)
                            .setSystemRoute(isSystem)
                            .build());
                }
            }
            return this;
        }

        /**
         * Adds a route to the provider
         */
        @NonNull
        public Builder addRoute(@NonNull MediaRoute2Info route) {
            Objects.requireNonNull(route, "route must not be null");

            if (mRoutes.containsKey(route.getOriginalId())) {
                throw new IllegalArgumentException("A route with the same id is already added");
            }
            if (mUniqueId != null) {
                mRoutes.put(route.getOriginalId(),
                        new MediaRoute2Info.Builder(route).setProviderId(mUniqueId).build());
            } else {
                mRoutes.put(route.getOriginalId(), route);
            }
            return this;
        }

        /**
         * Adds a list of routes to the provider
         */
        @NonNull
        public Builder addRoutes(@NonNull Collection<MediaRoute2Info> routes) {
            Objects.requireNonNull(routes, "routes must not be null");

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
            return new MediaRoute2ProviderInfo(this);
        }
    }
}
