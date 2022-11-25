/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Allows applications to customize the list of routes used for media routing (for example, in the
 * System UI Output Switcher).
 *
 * @see MediaRouter2#setRouteListingPreference
 */
public final class RouteListingPreference implements Parcelable {

    @NonNull
    public static final Creator<RouteListingPreference> CREATOR =
            new Creator<>() {
                @Override
                public RouteListingPreference createFromParcel(Parcel in) {
                    return new RouteListingPreference(in);
                }

                @Override
                public RouteListingPreference[] newArray(int size) {
                    return new RouteListingPreference[size];
                }
            };

    @NonNull private final List<Item> mItems;

    /**
     * Creates an instance with the given values.
     *
     * @param items See {@link #getItems()}.
     */
    public RouteListingPreference(@NonNull List<Item> items) {
        mItems = List.copyOf(Objects.requireNonNull(items));
    }

    private RouteListingPreference(Parcel in) {
        List<Item> items =
                in.readParcelableList(new ArrayList<>(), Item.class.getClassLoader(), Item.class);
        mItems = List.copyOf(items);
    }

    /**
     * Returns an unmodifiable list containing the items that the app wants to be listed for media
     * routing.
     */
    @NonNull
    public List<Item> getItems() {
        return mItems;
    }

    // RouteListingPreference Parcelable implementation.

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelableList(mItems, flags);
    }

    // Equals and hashCode.

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RouteListingPreference)) {
            return false;
        }
        RouteListingPreference that = (RouteListingPreference) other;
        return mItems.equals(that.mItems);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mItems);
    }

    // Internal classes.

    /** Holds preference information for a specific route in a media routing listing. */
    public static final class Item implements Parcelable {

        @NonNull
        public static final Creator<Item> CREATOR =
                new Creator<>() {
                    @Override
                    public Item createFromParcel(Parcel in) {
                        return new Item(in);
                    }

                    @Override
                    public Item[] newArray(int size) {
                        return new Item[size];
                    }
                };

        @NonNull private final String mRouteId;

        /**
         * Creates an instance with the given value.
         *
         * @param routeId See {@link #getRouteId()}. Must not be empty.
         */
        public Item(@NonNull String routeId) {
            Preconditions.checkArgument(!TextUtils.isEmpty(routeId));
            mRouteId = routeId;
        }

        private Item(Parcel in) {
            String routeId = in.readString();
            Preconditions.checkArgument(!TextUtils.isEmpty(routeId));
            mRouteId = routeId;
        }

        /** Returns the id of the route that corresponds to this route listing preference item. */
        @NonNull
        public String getRouteId() {
            return mRouteId;
        }

        // Item Parcelable implementation.

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mRouteId);
        }

        // Equals and hashCode.

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Item)) {
                return false;
            }
            Item item = (Item) other;
            return mRouteId.equals(item.mRouteId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRouteId);
        }
    }
}
