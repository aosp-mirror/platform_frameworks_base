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
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Allows applications to customize the list of routes used for media routing (for example, in the
 * System UI Output Switcher).
 *
 * @see MediaRouter2#setRouteListingPreference
 * @see Item
 */
public final class RouteListingPreference implements Parcelable {

    /**
     * {@link Intent} action that the system uses to take the user the app when the user selects an
     * {@link Item} whose {@link Item#getSelectionBehavior() selection behavior} is {@link
     * Item#SELECTION_BEHAVIOR_GO_TO_APP}.
     *
     * <p>The launched intent will identify the selected item using the extra identified by {@link
     * #EXTRA_ROUTE_ID}.
     *
     * @see #getLinkedItemComponentName()
     * @see Item#SELECTION_BEHAVIOR_GO_TO_APP
     */
    public static final String ACTION_TRANSFER_MEDIA = "android.media.action.TRANSFER_MEDIA";

    /**
     * {@link Intent} string extra key that contains the {@link Item#getRouteId() id} of the route
     * to transfer to, as part of an {@link #ACTION_TRANSFER_MEDIA} intent.
     *
     * @see #getLinkedItemComponentName()
     * @see Item#SELECTION_BEHAVIOR_GO_TO_APP
     */
    public static final String EXTRA_ROUTE_ID = "android.media.extra.ROUTE_ID";

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
    private final boolean mUseSystemOrdering;
    @Nullable private final ComponentName mLinkedItemComponentName;

    private RouteListingPreference(Builder builder) {
        mItems = builder.mItems;
        mUseSystemOrdering = builder.mUseSystemOrdering;
        mLinkedItemComponentName = builder.mLinkedItemComponentName;
    }

    private RouteListingPreference(Parcel in) {
        List<Item> items =
                in.readParcelableList(new ArrayList<>(), Item.class.getClassLoader(), Item.class);
        mItems = List.copyOf(items);
        mUseSystemOrdering = in.readBoolean();
        mLinkedItemComponentName = ComponentName.readFromParcel(in);
    }

    /**
     * Returns an unmodifiable list containing the {@link Item items} that the app wants to be
     * listed for media routing.
     */
    @NonNull
    public List<Item> getItems() {
        return mItems;
    }

    /**
     * Returns true if the application would like media route listing to use the system's ordering
     * strategy, or false if the application would like route listing to respect the ordering
     * obtained from {@link #getItems()}.
     *
     * <p>The system's ordering strategy is implementation-dependent, but may take into account each
     * route's recency or frequency of use in order to rank them.
     */
    public boolean getUseSystemOrdering() {
        return mUseSystemOrdering;
    }

    /**
     * Returns a {@link ComponentName} for navigating to the application.
     *
     * <p>Must not be null if any of the {@link #getItems() items} of this route listing preference
     * has {@link Item#getSelectionBehavior() selection behavior} {@link
     * Item#SELECTION_BEHAVIOR_GO_TO_APP}.
     *
     * <p>The system navigates to the application when the user selects {@link Item} with {@link
     * Item#SELECTION_BEHAVIOR_GO_TO_APP} by launching an intent to the returned {@link
     * ComponentName}, using action {@link #ACTION_TRANSFER_MEDIA}, with the extra {@link
     * #EXTRA_ROUTE_ID}.
     */
    @Nullable
    public ComponentName getLinkedItemComponentName() {
        return mLinkedItemComponentName;
    }

    // RouteListingPreference Parcelable implementation.

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelableList(mItems, flags);
        dest.writeBoolean(mUseSystemOrdering);
        ComponentName.writeToParcel(mLinkedItemComponentName, dest);
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
        return mItems.equals(that.mItems)
                && mUseSystemOrdering == that.mUseSystemOrdering
                && Objects.equals(mLinkedItemComponentName, that.mLinkedItemComponentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mItems, mUseSystemOrdering, mLinkedItemComponentName);
    }

    /** Builder for {@link RouteListingPreference}. */
    public static final class Builder {

        private List<Item> mItems;
        private boolean mUseSystemOrdering;
        private ComponentName mLinkedItemComponentName;

        /** Creates a new instance with default values (documented in the setters). */
        public Builder() {
            mItems = List.of();
            mUseSystemOrdering = true;
        }

        /**
         * See {@link #getItems()}
         *
         * <p>The default value is an empty list.
         */
        @NonNull
        public Builder setItems(@NonNull List<Item> items) {
            mItems = List.copyOf(Objects.requireNonNull(items));
            return this;
        }

        /**
         * See {@link #getUseSystemOrdering()}
         *
         * <p>The default value is {@code true}.
         */
        // Lint requires "isUseSystemOrdering", but "getUseSystemOrdering" is a better name.
        @SuppressWarnings("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setUseSystemOrdering(boolean useSystemOrdering) {
            mUseSystemOrdering = useSystemOrdering;
            return this;
        }

        /**
         * See {@link #getLinkedItemComponentName()}.
         *
         * <p>The default value is {@code null}.
         */
        @NonNull
        public Builder setLinkedItemComponentName(@Nullable ComponentName linkedItemComponentName) {
            mLinkedItemComponentName = linkedItemComponentName;
            return this;
        }

        /**
         * Creates and returns a new {@link RouteListingPreference} instance with the given
         * parameters.
         */
        @NonNull
        public RouteListingPreference build() {
            return new RouteListingPreference(this);
        }
    }

    /** Holds preference information for a specific route in a {@link RouteListingPreference}. */
    public static final class Item implements Parcelable {

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"SELECTION_BEHAVIOR_"},
                value = {
                    SELECTION_BEHAVIOR_NONE,
                    SELECTION_BEHAVIOR_TRANSFER,
                    SELECTION_BEHAVIOR_GO_TO_APP
                })
        public @interface SelectionBehavior {}

        /** The corresponding route is not selectable by the user. */
        public static final int SELECTION_BEHAVIOR_NONE = 0;
        /** If the user selects the corresponding route, the media transfers to the said route. */
        public static final int SELECTION_BEHAVIOR_TRANSFER = 1;
        /**
         * If the user selects the corresponding route, the system takes the user to the
         * application.
         *
         * <p>The system uses {@link #getLinkedItemComponentName()} in order to navigate to the app.
         */
        public static final int SELECTION_BEHAVIOR_GO_TO_APP = 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                flag = true,
                prefix = {"FLAG_"},
                value = {FLAG_ONGOING_SESSION, FLAG_ONGOING_SESSION_MANAGED, FLAG_SUGGESTED})
        public @interface Flags {}

        /**
         * The corresponding route is already hosting a session with the app that owns this listing
         * preference.
         */
        public static final int FLAG_ONGOING_SESSION = 1;

        /**
         * Signals that the ongoing session on the corresponding route is managed by the current
         * user of the app.
         *
         * <p>The system can use this flag to provide visual indication that the route is not only
         * hosting a session, but also that the user has ownership over said session.
         *
         * <p>This flag is ignored if {@link #FLAG_ONGOING_SESSION} is not set, or if the
         * corresponding route is not currently selected.
         *
         * <p>This flag does not affect volume adjustment (see {@link VolumeProvider}, and {@link
         * MediaRoute2Info#getVolumeHandling()}), or any aspect other than the visual representation
         * of the corresponding item.
         */
        public static final int FLAG_ONGOING_SESSION_MANAGED = 1 << 1;

        /**
         * The corresponding route is specially likely to be selected by the user.
         *
         * <p>A UI reflecting this preference may reserve a specific space for suggested routes,
         * making it more accessible to the user. If the number of suggested routes exceeds the
         * number supported by the UI, the routes listed first in {@link
         * RouteListingPreference#getItems()} will take priority.
         */
        public static final int FLAG_SUGGESTED = 1 << 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"SUBTEXT_"},
                value = {
                    SUBTEXT_NONE,
                    SUBTEXT_ERROR_UNKNOWN,
                    SUBTEXT_SUBSCRIPTION_REQUIRED,
                    SUBTEXT_DOWNLOADED_CONTENT_ROUTING_DISALLOWED,
                    SUBTEXT_AD_ROUTING_DISALLOWED,
                    SUBTEXT_DEVICE_LOW_POWER,
                    SUBTEXT_UNAUTHORIZED,
                    SUBTEXT_TRACK_UNSUPPORTED,
                    SUBTEXT_CUSTOM
                })
        public @interface SubText {}

        /** The corresponding route has no associated subtext. */
        public static final int SUBTEXT_NONE = 0;
        /**
         * The corresponding route's subtext must indicate that it is not available because of an
         * unknown error.
         */
        public static final int SUBTEXT_ERROR_UNKNOWN = 1;
        /**
         * The corresponding route's subtext must indicate that it requires a special subscription
         * in order to be available for routing.
         */
        public static final int SUBTEXT_SUBSCRIPTION_REQUIRED = 2;
        /**
         * The corresponding route's subtext must indicate that downloaded content cannot be routed
         * to it.
         */
        public static final int SUBTEXT_DOWNLOADED_CONTENT_ROUTING_DISALLOWED = 3;
        /**
         * The corresponding route's subtext must indicate that it is not available because an ad is
         * in progress.
         */
        public static final int SUBTEXT_AD_ROUTING_DISALLOWED = 4;
        /**
         * The corresponding route's subtext must indicate that it is not available because the
         * device is in low-power mode.
         */
        public static final int SUBTEXT_DEVICE_LOW_POWER = 5;
        /**
         * The corresponding route's subtext must indicate that it is not available because the user
         * is not authorized to route to it.
         */
        public static final int SUBTEXT_UNAUTHORIZED = 6;
        /**
         * The corresponding route's subtext must indicate that it is not available because the
         * device does not support the current media track.
         */
        public static final int SUBTEXT_TRACK_UNSUPPORTED = 7;
        /**
         * The corresponding route's subtext must be obtained from {@link
         * #getCustomSubtextMessage()}.
         *
         * <p>Applications should strongly prefer one of the other disable reasons (for the full
         * list, see {@link #getSubText()}) in order to guarantee correct localization and rendering
         * across all form factors.
         */
        public static final int SUBTEXT_CUSTOM = 10000;

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
        @SelectionBehavior private final int mSelectionBehavior;
        @Flags private final int mFlags;
        @SubText private final int mSubText;

        @Nullable private final CharSequence mCustomSubtextMessage;

        private Item(@NonNull Builder builder) {
            mRouteId = builder.mRouteId;
            mSelectionBehavior = builder.mSelectionBehavior;
            mFlags = builder.mFlags;
            mSubText = builder.mSubText;
            mCustomSubtextMessage = builder.mCustomSubtextMessage;
            validateCustomMessageSubtext();
        }

        private Item(Parcel in) {
            mRouteId = in.readString();
            Preconditions.checkArgument(!TextUtils.isEmpty(mRouteId));
            mSelectionBehavior = in.readInt();
            mFlags = in.readInt();
            mSubText = in.readInt();
            mCustomSubtextMessage = in.readCharSequence();
            validateCustomMessageSubtext();
        }

        /**
         * Returns the id of the route that corresponds to this route listing preference item.
         *
         * @see MediaRoute2Info#getId()
         */
        @NonNull
        public String getRouteId() {
            return mRouteId;
        }

        /**
         * Returns the behavior that the corresponding route has if the user selects it.
         *
         * @see #SELECTION_BEHAVIOR_NONE
         * @see #SELECTION_BEHAVIOR_TRANSFER
         * @see #SELECTION_BEHAVIOR_GO_TO_APP
         */
        public int getSelectionBehavior() {
            return mSelectionBehavior;
        }

        /**
         * Returns the flags associated to the route that corresponds to this item.
         *
         * @see #FLAG_ONGOING_SESSION
         * @see #FLAG_ONGOING_SESSION_MANAGED
         * @see #FLAG_SUGGESTED
         */
        @Flags
        public int getFlags() {
            return mFlags;
        }

        /**
         * Returns the type of subtext associated to this route.
         *
         * <p>Subtext types other than {@link #SUBTEXT_NONE} and {@link #SUBTEXT_CUSTOM} must not
         * have {@link #SELECTION_BEHAVIOR_TRANSFER}.
         *
         * <p>If this method returns {@link #SUBTEXT_CUSTOM}, then the subtext is obtained form
         * {@link #getCustomSubtextMessage()}.
         *
         * @see #SUBTEXT_NONE
         * @see #SUBTEXT_ERROR_UNKNOWN
         * @see #SUBTEXT_SUBSCRIPTION_REQUIRED
         * @see #SUBTEXT_DOWNLOADED_CONTENT_ROUTING_DISALLOWED
         * @see #SUBTEXT_AD_ROUTING_DISALLOWED
         * @see #SUBTEXT_DEVICE_LOW_POWER
         * @see #SUBTEXT_UNAUTHORIZED
         * @see #SUBTEXT_TRACK_UNSUPPORTED
         * @see #SUBTEXT_CUSTOM
         */
        @SubText
        public int getSubText() {
            return mSubText;
        }

        /**
         * Returns a human-readable {@link CharSequence} providing the subtext for the corresponding
         * route.
         *
         * <p>This value is ignored if the {@link #getSubText() subtext} for this item is not {@link
         * #SUBTEXT_CUSTOM}..
         *
         * <p>Applications must provide a localized message that matches the system's locale. See
         * {@link Locale#getDefault()}.
         *
         * <p>Applications should avoid using custom messages (and instead use one of non-custom
         * subtexts listed in {@link #getSubText()} in order to guarantee correct visual
         * representation and localization on all form factors.
         */
        @Nullable
        public CharSequence getCustomSubtextMessage() {
            return mCustomSubtextMessage;
        }

        // Item Parcelable implementation.

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mRouteId);
            dest.writeInt(mSelectionBehavior);
            dest.writeInt(mFlags);
            dest.writeInt(mSubText);
            dest.writeCharSequence(mCustomSubtextMessage);
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
                    && mSelectionBehavior == item.mSelectionBehavior
                    && mFlags == item.mFlags
                    && mSubText == item.mSubText
                    && TextUtils.equals(mCustomSubtextMessage, item.mCustomSubtextMessage);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    mRouteId, mSelectionBehavior, mFlags, mSubText, mCustomSubtextMessage);
        }

        // Internal methods.

        private void validateCustomMessageSubtext() {
            Preconditions.checkArgument(
                    mSubText != SUBTEXT_CUSTOM || mCustomSubtextMessage != null,
                    "The custom subtext message cannot be null if subtext is SUBTEXT_CUSTOM.");
        }

        // Internal classes.

        /** Builder for {@link Item}. */
        public static final class Builder {

            private final String mRouteId;
            private int mSelectionBehavior;
            private int mFlags;
            private int mSubText;
            private CharSequence mCustomSubtextMessage;

            /**
             * Constructor.
             *
             * @param routeId See {@link Item#getRouteId()}.
             */
            public Builder(@NonNull String routeId) {
                Preconditions.checkArgument(!TextUtils.isEmpty(routeId));
                mRouteId = routeId;
                mSelectionBehavior = SELECTION_BEHAVIOR_TRANSFER;
                mSubText = SUBTEXT_NONE;
            }

            /**
             * See {@link Item#getSelectionBehavior()}.
             *
             * <p>The default value is {@link #ACTION_TRANSFER_MEDIA}.
             */
            @NonNull
            public Builder setSelectionBehavior(int selectionBehavior) {
                mSelectionBehavior = selectionBehavior;
                return this;
            }

            /**
             * See {@link Item#getFlags()}.
             *
             * <p>The default value is zero (no flags).
             */
            @NonNull
            public Builder setFlags(int flags) {
                mFlags = flags;
                return this;
            }

            /**
             * See {@link Item#getSubText()}.
             *
             * <p>The default value is {@link #SUBTEXT_NONE}.
             */
            @NonNull
            public Builder setSubText(int subText) {
                mSubText = subText;
                return this;
            }

            /**
             * See {@link Item#getCustomSubtextMessage()}.
             *
             * <p>The default value is {@code null}.
             */
            @NonNull
            public Builder setCustomSubtextMessage(@Nullable CharSequence customSubtextMessage) {
                mCustomSubtextMessage = customSubtextMessage;
                return this;
            }

            /** Creates and returns a new {@link Item} with the given parameters. */
            @NonNull
            public Item build() {
                return new Item(this);
            }
        }
    }
}
