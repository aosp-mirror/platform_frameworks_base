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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


/**
 * @hide
 */
public final class RouteDiscoveryRequest implements Parcelable {
    @NonNull
    public static final Creator<RouteDiscoveryRequest> CREATOR =
            new Creator<RouteDiscoveryRequest>() {
                @Override
                public RouteDiscoveryRequest createFromParcel(Parcel in) {
                    return new RouteDiscoveryRequest(in);
                }

                @Override
                public RouteDiscoveryRequest[] newArray(int size) {
                    return new RouteDiscoveryRequest[size];
                }
            };

    @NonNull
    private final List<String> mRouteTypes;
    private final boolean mActiveScan;
    @Nullable
    private final Bundle mExtras;

    /**
     * @hide
     */
    public static final RouteDiscoveryRequest EMPTY =
            new Builder(Collections.emptyList(), false).build();

    RouteDiscoveryRequest(@NonNull Builder builder) {
        mRouteTypes = builder.mRouteTypes;
        mActiveScan = builder.mActiveScan;
        mExtras = builder.mExtras;
    }

    RouteDiscoveryRequest(@NonNull Parcel in) {
        mRouteTypes = in.createStringArrayList();
        mActiveScan = in.readBoolean();
        mExtras = in.readBundle();
    }

    @NonNull
    public List<String> getRouteTypes() {
        return mRouteTypes;
    }

    public boolean isActiveScan() {
        return mActiveScan;
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
        dest.writeStringList(mRouteTypes);
        dest.writeBoolean(mActiveScan);
        dest.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("RouteDiscoveryRequest{ ")
                .append("routeTypes={")
                .append(String.join(", ", mRouteTypes))
                .append("}")
                .append(", activeScan=")
                .append(mActiveScan)
                .append(" }");

        return result.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RouteDiscoveryRequest)) {
            return false;
        }
        RouteDiscoveryRequest other = (RouteDiscoveryRequest) o;
        return Objects.equals(mRouteTypes, other.mRouteTypes)
                && mActiveScan == other.mActiveScan;
    }

    /**
     * Builder for {@link RouteDiscoveryRequest}.
     */
    public static final class Builder {
        List<String> mRouteTypes;
        boolean mActiveScan;
        Bundle mExtras;

        public Builder(@NonNull List<String> routeTypes, boolean activeScan) {
            mRouteTypes = new ArrayList<>(
                    Objects.requireNonNull(routeTypes, "routeTypes must not be null"));
            mActiveScan = activeScan;
        }

        public Builder(@NonNull RouteDiscoveryRequest request) {
            Objects.requireNonNull(request, "request must not be null");

            mRouteTypes = request.getRouteTypes();
            mActiveScan = request.isActiveScan();
            mExtras = request.getExtras();
        }

        /**
         * A constructor to combine all of the requests into a single request.
         * It ignores extras of requests.
         */
        Builder(@NonNull Collection<RouteDiscoveryRequest> requests) {
            Set<String> routeTypeSet = new HashSet<>();
            mActiveScan = false;
            for (RouteDiscoveryRequest request : requests) {
                routeTypeSet.addAll(request.mRouteTypes);
                mActiveScan |= request.mActiveScan;
            }
            mRouteTypes = new ArrayList<>(routeTypeSet);
        }

        /**
         * Sets route types to discover.
         */
        public Builder setRouteTypes(@NonNull List<String> routeTypes) {
            mRouteTypes = new ArrayList<>(
                    Objects.requireNonNull(routeTypes, "routeTypes must not be null"));
            return this;
        }

        /**
         * Sets if active scanning should be performed.
         */
        public Builder setActiveScan(boolean activeScan) {
            mActiveScan = activeScan;
            return this;
        }

        /**
         * Sets the extras of the route.
         * @hide
         */
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link RouteDiscoveryRequest}.
         */
        public RouteDiscoveryRequest build() {
            return new RouteDiscoveryRequest(this);
        }
    }
}
