/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.session;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Information about a route, including its display name, a way to identify it,
 * and the ways it can be connected to.
 * @hide
 */
public final class RouteInfo implements Parcelable {
    private final String mName;
    private final String mId;
    private final String mProviderId;
    private final List<RouteOptions> mOptions;

    private RouteInfo(String id, String name, String providerId,
            List<RouteOptions> connRequests) {
        mId = id;
        mName = name;
        mProviderId = providerId;
        mOptions = connRequests;
    }

    private RouteInfo(Parcel in) {
        mId = in.readString();
        mName = in.readString();
        mProviderId = in.readString();
        mOptions = new ArrayList<RouteOptions>();
        in.readTypedList(mOptions, RouteOptions.CREATOR);
    }

    /**
     * Get the displayable name of this route.
     *
     * @return A short, user readable name for this route
     */
    public String getName() {
        return mName;
    }

    /**
     * Get the unique id for this route.
     *
     * @return A unique route id.
     */
    public String getId() {
        return mId;
    }

    /**
     * Get the package name of this route's provider.
     *
     * @return The package name of this route's provider.
     */
    public String getProvider() {
        return mProviderId;
    }

    /**
     * Get the set of connections that may be used with this route.
     *
     * @return An array of connection requests that may be used to connect
     */
    public List<RouteOptions> getConnectionMethods() {
        return mOptions;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mName);
        dest.writeString(mProviderId);
        dest.writeTypedList(mOptions);
    }

    @Override
    public String toString() {
        StringBuilder bob = new StringBuilder();
        bob.append("RouteInfo: id=").append(mId).append(", name=").append(mName)
                .append(", provider=").append(mProviderId).append(", options={");
        for (int i = 0; i < mOptions.size(); i++) {
            if (i != 0) {
                bob.append(", ");
            }
            bob.append(mOptions.get(i).toString());
        }
        bob.append("}");
        return bob.toString();
    }

    public static final Parcelable.Creator<RouteInfo> CREATOR
            = new Parcelable.Creator<RouteInfo>() {
        @Override
        public RouteInfo createFromParcel(Parcel in) {
            return new RouteInfo(in);
        }

        @Override
        public RouteInfo[] newArray(int size) {
            return new RouteInfo[size];
        }
    };

    /**
     * Helper for creating MediaRouteInfos. A route must have a name and an id.
     * While options are not strictly required the route cannot be connected to
     * without at least one set of options.
     */
    public static final class Builder {
        private String mName;
        private String mId;
        private String mProviderPackage;
        private ArrayList<RouteOptions> mOptions;

        /**
         * Copies an existing route info object. TODO Remove once we have
         * helpers for creating route infos.
         *
         * @param from The existing info to copy.
         */
        public Builder(RouteInfo from) {
            mOptions = new ArrayList<RouteOptions>(from.getConnectionMethods());
            mName = from.mName;
            mId = from.mId;
            mProviderPackage = from.mProviderId;
        }

        public Builder() {
            mOptions = new ArrayList<RouteOptions>();
        }

        /**
         * Set the user visible name for this route.
         *
         * @param name The name of the route
         * @return The builder for easy chaining.
         */
        public Builder setName(String name) {
            mName = name;
            return this;
        }

        /**
         * Set the id of the route. This should be unique to the provider.
         *
         * @param id The unique id of the route.
         * @return The builder for easy chaining.
         */
        public Builder setId(String id) {
            mId = id;
            return this;
        }

        /**
         * @hide
         */
        public Builder setProviderId(String packageName) {
            mProviderPackage = packageName;
            return this;
        }

        /**
         * Add a set of {@link RouteOptions} to the route. Multiple options
         * may be added to the same route.
         *
         * @param options The options to add to this route.
         * @return The builder for easy chaining.
         */
        public Builder addRouteOptions(RouteOptions options) {
            mOptions.add(options);
            return this;
        }

        /**
         * Clear the set of {@link RouteOptions} on the route.
         *
         * @return The builder for easy chaining
         */
        public Builder clearRouteOptions() {
            mOptions.clear();
            return this;
        }

        /**
         * Build a new MediaRouteInfo.
         *
         * @return A new MediaRouteInfo with the values that were set.
         */
        public RouteInfo build() {
            if (TextUtils.isEmpty(mName)) {
                throw new IllegalArgumentException("Must set a name before building");
            }
            if (TextUtils.isEmpty(mId)) {
                throw new IllegalArgumentException("Must set an id before building");
            }
            return new RouteInfo(mId, mName, mProviderPackage, mOptions);
        }

        /**
         * Get the current number of options that have been added to this
         * builder.
         *
         * @return The number of options that have been added.
         */
        public int getOptionsSize() {
            return mOptions.size();
        }
    }
}
