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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                flag = true,
                prefix = {"FLAG_"},
                value = {FLAG_ONGOING_SESSION, FLAG_SUGGESTED_ROUTE})
        public @interface Flags {}

        /**
         * The corresponding route is already hosting a session with the app that owns this listing
         * preference.
         */
        public static final int FLAG_ONGOING_SESSION = 1;

        /**
         * The corresponding route is specially likely to be selected by the user.
         *
         * <p>A UI reflecting this preference may reserve a specific space for suggested routes,
         * making it more accessible to the user. If the number of suggested routes exceeds the
         * number supported by the UI, the routes listed first in {@link
         * RouteListingPreference#getItems()} will take priority.
         */
        public static final int FLAG_SUGGESTED_ROUTE = 1 << 1;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"DISABLE_REASON_"},
                value = {DISABLE_REASON_NONE, DISABLE_REASON_SUBSCRIPTION_REQUIRED})
        public @interface DisableReason {}

        /** The corresponding route is available for routing. */
        public static final int DISABLE_REASON_NONE = 0;
        /**
         * The corresponding route requires a special subscription in order to be available for
         * routing.
         */
        public static final int DISABLE_REASON_SUBSCRIPTION_REQUIRED = 1;

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
        @Flags private final int mFlags;
        @DisableReason private final int mDisableReason;

        /**
         * Creates an instance with the given value.
         *
         * @param routeId See {@link #getRouteId()}. Must not be empty.
         * @param flags See {@link #getFlags()}.
         * @param disableReason See {@link #getDisableReason()}.
         */
        public Item(@NonNull String routeId, @Flags int flags, @DisableReason int disableReason) {
            Preconditions.checkArgument(!TextUtils.isEmpty(routeId));
            mRouteId = routeId;
            mFlags = flags;
            mDisableReason = disableReason;
        }

        private Item(Parcel in) {
            String routeId = in.readString();
            Preconditions.checkArgument(!TextUtils.isEmpty(routeId));
            mRouteId = routeId;
            mFlags = in.readInt();
            mDisableReason = in.readInt();
        }

        /** Returns the id of the route that corresponds to this route listing preference item. */
        @NonNull
        public String getRouteId() {
            return mRouteId;
        }

        /**
         * Returns the flags associated to the route that corresponds to this item.
         *
         * @see #FLAG_ONGOING_SESSION
         * @see #FLAG_SUGGESTED_ROUTE
         */
        @Flags
        public int getFlags() {
            return mFlags;
        }

        /**
         * Returns the reason for the corresponding route to be disabled, or {@link
         * #DISABLE_REASON_NONE} if the route is not disabled.
         *
         * @see #DISABLE_REASON_NONE
         * @see #DISABLE_REASON_SUBSCRIPTION_REQUIRED
         */
        @DisableReason
        public int getDisableReason() {
            return mDisableReason;
        }

        // Item Parcelable implementation.

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mRouteId);
            dest.writeInt(mFlags);
            dest.writeInt(mDisableReason);
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
            return mRouteId.equals(item.mRouteId)
                    && mFlags == item.mFlags
                    && mDisableReason == item.mDisableReason;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRouteId, mFlags, mDisableReason);
        }
    }
}
