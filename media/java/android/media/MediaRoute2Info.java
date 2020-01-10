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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Describes the properties of a route.
 * @hide
 */
public final class MediaRoute2Info implements Parcelable {
    @NonNull
    public static final Creator<MediaRoute2Info> CREATOR = new Creator<MediaRoute2Info>() {
        @Override
        public MediaRoute2Info createFromParcel(Parcel in) {
            return new MediaRoute2Info(in);
        }

        @Override
        public MediaRoute2Info[] newArray(int size) {
            return new MediaRoute2Info[size];
        }
    };

    /** @hide */
    @IntDef({CONNECTION_STATE_DISCONNECTED, CONNECTION_STATE_CONNECTING,
            CONNECTION_STATE_CONNECTED})
    @Retention(RetentionPolicy.SOURCE)
    private @interface ConnectionState {}

    /**
     * The default connection state indicating the route is disconnected.
     *
     * @see #getConnectionState
     */
    public static final int CONNECTION_STATE_DISCONNECTED = 0;

    /**
     * A connection state indicating the route is in the process of connecting and is not yet
     * ready for use.
     *
     * @see #getConnectionState
     */
    public static final int CONNECTION_STATE_CONNECTING = 1;

    /**
     * A connection state indicating the route is connected.
     *
     * @see #getConnectionState
     */
    public static final int CONNECTION_STATE_CONNECTED = 2;

    /**
     * Playback information indicating the playback volume is fixed, i&#46;e&#46; it cannot be
     * controlled from this object. An example of fixed playback volume is a remote player,
     * playing over HDMI where the user prefers to control the volume on the HDMI sink, rather
     * than attenuate at the source.
     *
     * @see #getVolumeHandling()
     */
    public static final int PLAYBACK_VOLUME_FIXED = 0;
    /**
     * Playback information indicating the playback volume is variable and can be controlled
     * from this object.
     *
     * @see #getVolumeHandling()
     */
    public static final int PLAYBACK_VOLUME_VARIABLE = 1;

    /** @hide */
    @IntDef({
            DEVICE_TYPE_UNKNOWN, DEVICE_TYPE_TV,
            DEVICE_TYPE_SPEAKER, DEVICE_TYPE_BLUETOOTH})
    @Retention(RetentionPolicy.SOURCE)
    private @interface DeviceType {}

    /**
     * The default receiver device type of the route indicating the type is unknown.
     *
     * @see #getDeviceType
     * @hide
     */
    public static final int DEVICE_TYPE_UNKNOWN = 0;

    /**
     * A receiver device type of the route indicating the presentation of the media is happening
     * on a TV.
     *
     * @see #getDeviceType
     */
    public static final int DEVICE_TYPE_TV = 1;

    /**
     * A receiver device type of the route indicating the presentation of the media is happening
     * on a speaker.
     *
     * @see #getDeviceType
     */
    public static final int DEVICE_TYPE_SPEAKER = 2;

    /**
     * A receiver device type of the route indicating the presentation of the media is happening
     * on a bluetooth device such as a bluetooth speaker.
     *
     * @see #getDeviceType
     * @hide
     */
    public static final int DEVICE_TYPE_BLUETOOTH = 3;

    @NonNull
    final String mId;
    @Nullable
    final String mProviderId;
    @NonNull
    final CharSequence mName;
    @Nullable
    final CharSequence mDescription;
    @Nullable
    final @ConnectionState int mConnectionState;
    @Nullable
    final Uri mIconUri;
    @Nullable
    final String mClientPackageName;
    @NonNull
    final List<String> mRouteTypes;
    final int mVolume;
    final int mVolumeMax;
    final int mVolumeHandling;
    final @DeviceType int mDeviceType;
    @Nullable
    final Bundle mExtras;

    MediaRoute2Info(@NonNull Builder builder) {
        mId = builder.mId;
        mProviderId = builder.mProviderId;
        mName = builder.mName;
        mDescription = builder.mDescription;
        mConnectionState = builder.mConnectionState;
        mIconUri = builder.mIconUri;
        mClientPackageName = builder.mClientPackageName;
        mRouteTypes = builder.mRouteTypes;
        mVolume = builder.mVolume;
        mVolumeMax = builder.mVolumeMax;
        mVolumeHandling = builder.mVolumeHandling;
        mDeviceType = builder.mDeviceType;
        mExtras = builder.mExtras;
    }

    MediaRoute2Info(@NonNull Parcel in) {
        mId = in.readString();
        mProviderId = in.readString();
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mConnectionState = in.readInt();
        mIconUri = in.readParcelable(null);
        mClientPackageName = in.readString();
        mRouteTypes = in.createStringArrayList();
        mVolume = in.readInt();
        mVolumeMax = in.readInt();
        mVolumeHandling = in.readInt();
        mDeviceType = in.readInt();
        mExtras = in.readBundle();
    }

    /**
     * @hide
     */
    public static String toUniqueId(String providerId, String routeId) {
        return providerId + ":" + routeId;
    }

    /**
     * Returns true if the route info has all of the required field.
     * A route info only obtained from {@link com.android.server.media.MediaRouterService}
     * is valid.
     * @hide
     */
    //TODO: Reconsider the validity of a route info when fields are added.
    public boolean isValid() {
        if (TextUtils.isEmpty(getId()) || TextUtils.isEmpty(getName())
                || TextUtils.isEmpty(getProviderId())) {
            return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MediaRoute2Info)) {
            return false;
        }
        MediaRoute2Info other = (MediaRoute2Info) obj;
        return Objects.equals(mId, other.mId)
                && Objects.equals(mProviderId, other.mProviderId)
                && Objects.equals(mName, other.mName)
                && Objects.equals(mDescription, other.mDescription)
                && (mConnectionState == other.mConnectionState)
                && Objects.equals(mIconUri, other.mIconUri)
                && Objects.equals(mClientPackageName, other.mClientPackageName)
                && Objects.equals(mRouteTypes, other.mRouteTypes)
                && (mVolume == other.mVolume)
                && (mVolumeMax == other.mVolumeMax)
                && (mVolumeHandling == other.mVolumeHandling)
                && (mDeviceType == other.mDeviceType)
                //TODO: This will be evaluated as false in most cases. Try not to.
                && Objects.equals(mExtras, other.mExtras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mName, mDescription, mConnectionState, mIconUri,
                mRouteTypes, mVolume, mVolumeMax, mVolumeHandling, mDeviceType);
    }

    /**
     * Gets the id of the route. The routes which are given by {@link MediaRouter2} will have
     * unique IDs.
     * <p>
     * In order to ensure uniqueness in {@link MediaRouter2} side, the value of this method
     * can be different from what was set in {@link MediaRoute2ProviderService}.
     *
     * @see Builder#setId(String)
     */
    @NonNull
    public String getId() {
        if (mProviderId != null) {
            return toUniqueId(mProviderId, mId);
        } else {
            return mId;
        }
    }

    /**
     * Gets the original id set by {@link Builder#setId(String)}.
     * @hide
     */
    @NonNull
    public String getOriginalId() {
        return mId;
    }

    /**
     * Gets the provider id of the route. It is assigned automatically by
     * {@link com.android.server.media.MediaRouterService}.
     *
     * @return provider id of the route or null if it's not set.
     * @hide
     */
    @Nullable
    public String getProviderId() {
        return mProviderId;
    }

    @NonNull
    public CharSequence getName() {
        return mName;
    }

    @Nullable
    public CharSequence getDescription() {
        return mDescription;
    }

    /**
     * Gets the connection state of the route.
     *
     * @return The connection state of this route: {@link #CONNECTION_STATE_DISCONNECTED},
     * {@link #CONNECTION_STATE_CONNECTING}, or {@link #CONNECTION_STATE_CONNECTED}.
     */
    @ConnectionState
    public int getConnectionState() {
        return mConnectionState;
    }

    /**
     * Gets the URI of the icon representing this route.
     * <p>
     * This icon will be used in picker UIs if available.
     *
     * @return The URI of the icon representing this route, or null if none.
     */
    @Nullable
    public Uri getIconUri() {
        return mIconUri;
    }

    /**
     * Gets the package name of the client that uses the route.
     * Returns null if no clients use this.
     * @hide
     */
    @Nullable
    public String getClientPackageName() {
        return mClientPackageName;
    }

    /**
     * Gets the supported categories of the route.
     */
    @NonNull
    public List<String> getRouteTypes() {
        return mRouteTypes;
    }

    //TODO: once device types are confirmed, reflect those into the comment.
    /**
     * Gets the type of the receiver device associated with this route.
     *
     * @return The type of the receiver device associated with this route:
     * {@link #DEVICE_TYPE_TV} or {@link #DEVICE_TYPE_SPEAKER}.
     */
    @DeviceType
    public int getDeviceType() {
        return mDeviceType;
    }

    /**
     * Gets the current volume of the route. This may be invalid if the route is not selected.
     */
    public int getVolume() {
        return mVolume;
    }

    /**
     * Gets the maximum volume of the route.
     */
    public int getVolumeMax() {
        return mVolumeMax;
    }

    /**
     * Gets information about how volume is handled on the route.
     *
     * @return {@link #PLAYBACK_VOLUME_FIXED} or {@link #PLAYBACK_VOLUME_VARIABLE}
     */
    public int getVolumeHandling() {
        return mVolumeHandling;
    }

    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Returns if the route contains at least one of the specified route types.
     *
     * @param routeTypes the list of route types to consider
     * @return true if the route contains at least one type in the list
     */
    public boolean containsRouteTypes(@NonNull Collection<String> routeTypes) {
        Objects.requireNonNull(routeTypes, "routeTypes must not be null");
        for (String routeType : routeTypes) {
            if (getRouteTypes().contains(routeType)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mProviderId);
        TextUtils.writeToParcel(mName, dest, flags);
        TextUtils.writeToParcel(mDescription, dest, flags);
        dest.writeInt(mConnectionState);
        dest.writeParcelable(mIconUri, flags);
        dest.writeString(mClientPackageName);
        dest.writeStringList(mRouteTypes);
        dest.writeInt(mVolume);
        dest.writeInt(mVolumeMax);
        dest.writeInt(mVolumeHandling);
        dest.writeInt(mDeviceType);
        dest.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("MediaRouteInfo{ ")
                .append("id=").append(getId())
                .append(", name=").append(getName())
                .append(", description=").append(getDescription())
                .append(", connectionState=").append(getConnectionState())
                .append(", iconUri=").append(getIconUri())
                .append(", volume=").append(getVolume())
                .append(", volumeMax=").append(getVolumeMax())
                .append(", volumeHandling=").append(getVolumeHandling())
                .append(", deviceType=").append(getDeviceType())
                .append(", providerId=").append(getProviderId())
                .append(" }");
        return result.toString();
    }

    /**
     * Builder for {@link MediaRoute2Info media route info}.
     */
    public static final class Builder {
        String mId;
        String mProviderId;
        CharSequence mName;
        CharSequence mDescription;
        @ConnectionState
        int mConnectionState;
        Uri mIconUri;
        String mClientPackageName;
        List<String> mRouteTypes;
        int mVolume;
        int mVolumeMax;
        int mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        @DeviceType
        int mDeviceType = DEVICE_TYPE_UNKNOWN;
        Bundle mExtras;

        public Builder(@NonNull String id, @NonNull CharSequence name) {
            setId(id);
            setName(name);
            mRouteTypes = new ArrayList<>();
        }

        public Builder(@NonNull MediaRoute2Info routeInfo) {
            if (routeInfo == null) {
                throw new IllegalArgumentException("route info must not be null");
            }

            setId(routeInfo.mId);
            if (!TextUtils.isEmpty(routeInfo.mProviderId)) {
                setProviderId(routeInfo.mProviderId);
            }
            setName(routeInfo.mName);
            mDescription = routeInfo.mDescription;
            mConnectionState = routeInfo.mConnectionState;
            mIconUri = routeInfo.mIconUri;
            setClientPackageName(routeInfo.mClientPackageName);
            setRouteTypes(routeInfo.mRouteTypes);
            setVolume(routeInfo.mVolume);
            setVolumeMax(routeInfo.mVolumeMax);
            setVolumeHandling(routeInfo.mVolumeHandling);
            setDeviceType(routeInfo.mDeviceType);
            if (routeInfo.mExtras != null) {
                mExtras = new Bundle(routeInfo.mExtras);
            }
        }

        /**
         * Sets the unique id of the route. The value given here must be unique for each of your
         * route.
         * <p>
         * In order to ensure uniqueness in {@link MediaRouter2} side, the value of
         * {@link MediaRoute2Info#getId()} can be different from what was set in
         * {@link MediaRoute2ProviderService}.
         * </p>
         *
         * @see MediaRoute2Info#getId()
         */
        @NonNull
        public Builder setId(@NonNull String id) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id must not be null or empty");
            }
            mId = id;
            return this;
        }

        /**
         * Sets the provider id of the route.
         * @hide
         */
        @NonNull
        public Builder setProviderId(@NonNull String providerId) {
            if (TextUtils.isEmpty(providerId)) {
                throw new IllegalArgumentException("providerId must not be null or empty");
            }
            mProviderId = providerId;
            return this;
        }

        /**
         * Sets the user-visible name of the route.
         */
        @NonNull
        public Builder setName(@NonNull CharSequence name) {
            Objects.requireNonNull(name, "name must not be null");
            mName = name;
            return this;
        }

        /**
         * Sets the user-visible description of the route.
         */
        @NonNull
        public Builder setDescription(@Nullable String description) {
            mDescription = description;
            return this;
        }

        /**
        * Sets the route's connection state.
        *
        * {@link #CONNECTION_STATE_DISCONNECTED},
        * {@link #CONNECTION_STATE_CONNECTING}, or
        * {@link #CONNECTION_STATE_CONNECTED}.
        */
        @NonNull
        public Builder setConnectionState(@ConnectionState int connectionState) {
            mConnectionState = connectionState;
            return this;
        }

        /**
         * Sets the URI of the icon representing this route.
         * <p>
         * This icon will be used in picker UIs if available.
         * </p><p>
         * The URI must be one of the following formats:
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         * </p>
         */
        @NonNull
        public Builder setIconUri(@Nullable Uri iconUri) {
            mIconUri = iconUri;
            return this;
        }

        /**
         * Sets the package name of the app using the route.
         */
        @NonNull
        public Builder setClientPackageName(@Nullable String packageName) {
            mClientPackageName = packageName;
            return this;
        }

        /**
         * Sets the types of the route.
         */
        @NonNull
        public Builder setRouteTypes(@NonNull Collection<String> routeTypes) {
            mRouteTypes = new ArrayList<>();
            return addRouteTypes(routeTypes);
        }

        /**
         * Adds types for the route.
         */
        @NonNull
        public Builder addRouteTypes(@NonNull Collection<String> routeTypes) {
            Objects.requireNonNull(routeTypes, "routeTypes must not be null");
            for (String routeType: routeTypes) {
                addRouteType(routeType);
            }
            return this;
        }

        /**
         * Add a type for the route.
         */
        @NonNull
        public Builder addRouteType(@NonNull String routeType) {
            if (TextUtils.isEmpty(routeType)) {
                throw new IllegalArgumentException("routeType must not be null or empty");
            }
            mRouteTypes.add(routeType);
            return this;
        }

        /**
         * Sets the route's current volume, or 0 if unknown.
         */
        @NonNull
        public Builder setVolume(int volume) {
            mVolume = volume;
            return this;
        }

        /**
         * Sets the route's maximum volume, or 0 if unknown.
         */
        @NonNull
        public Builder setVolumeMax(int volumeMax) {
            mVolumeMax = volumeMax;
            return this;
        }

        /**
         * Sets the route's volume handling.
         */
        @NonNull
        public Builder setVolumeHandling(int volumeHandling) {
            mVolumeHandling = volumeHandling;
            return this;
        }

        /**
         * Sets the route's device type.
         */
        @NonNull
        public Builder setDeviceType(@DeviceType int deviceType) {
            mDeviceType = deviceType;
            return this;
        }

        /**
         * Sets a bundle of extras for the route.
         */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link MediaRoute2Info media route info}.
         */
        @NonNull
        public MediaRoute2Info build() {
            return new MediaRoute2Info(this);
        }
    }
}
