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

import static android.media.MediaRouter2Utils.toUniqueId;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
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
    public @interface ConnectionState {}

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

    /** @hide */
    @IntDef({PLAYBACK_VOLUME_FIXED, PLAYBACK_VOLUME_VARIABLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlaybackVolume {}

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
            TYPE_UNKNOWN, TYPE_BUILTIN_SPEAKER, TYPE_WIRED_HEADSET,
            TYPE_WIRED_HEADPHONES, TYPE_BLUETOOTH_A2DP, TYPE_HDMI, TYPE_USB_DEVICE,
            TYPE_USB_ACCESSORY, TYPE_DOCK, TYPE_USB_HEADSET, TYPE_HEARING_AID,
            TYPE_REMOTE_TV, TYPE_REMOTE_SPEAKER, TYPE_GROUP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * The default route type indicating the type is unknown.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_UNKNOWN = 0;

    /**
     * A route type describing the speaker system (i.e. a mono speaker or stereo speakers) built
     * in a device.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_BUILTIN_SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;

    /**
     * A route type describing a headset, which is the combination of a headphones and microphone.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_WIRED_HEADSET = AudioDeviceInfo.TYPE_WIRED_HEADSET;

    /**
     * A route type describing a pair of wired headphones.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_WIRED_HEADPHONES = AudioDeviceInfo.TYPE_WIRED_HEADPHONES;

    /**
     * A route type indicating the presentation of the media is happening
     * on a bluetooth device such as a bluetooth speaker.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_BLUETOOTH_A2DP = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;

    /**
     * A route type describing an HDMI connection.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_HDMI = AudioDeviceInfo.TYPE_HDMI;

    /**
     * A route type describing a USB audio device.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_USB_DEVICE = AudioDeviceInfo.TYPE_USB_DEVICE;

    /**
     * A route type describing a USB audio device in accessory mode.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_USB_ACCESSORY = AudioDeviceInfo.TYPE_USB_ACCESSORY;

    /**
     * A route type describing the audio device associated with a dock.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_DOCK = AudioDeviceInfo.TYPE_DOCK;

    /**
     * A device type describing a USB audio headset.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_USB_HEADSET = AudioDeviceInfo.TYPE_USB_HEADSET;

    /**
     * A route type describing a Hearing Aid.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_HEARING_AID = AudioDeviceInfo.TYPE_HEARING_AID;

    /**
     * A route type indicating the presentation of the media is happening on a TV.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_REMOTE_TV = 1001;

    /**
     * A route type indicating the presentation of the media is happening on a speaker.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_REMOTE_SPEAKER = 1002;

    /**
     * A route type indicating the presentation of the media is happening on multiple devices.
     *
     * @see #getType
     * @hide
     */
    public static final int TYPE_GROUP = 2000;

    /**
     * Route feature: Live audio.
     * <p>
     * A route that supports live audio routing will allow the media audio stream
     * to be sent to supported destinations.  This can include internal speakers or
     * audio jacks on the device itself, A2DP devices, and more.
     * </p><p>
     * When a live audio route is selected, audio routing is transparent to the application.
     * All audio played on the media stream will be routed to the selected destination.
     * </p><p>
     * Refer to the class documentation for details about live audio routes.
     * </p>
     */
    public static final String FEATURE_LIVE_AUDIO = "android.media.route.feature.LIVE_AUDIO";

    /**
     * Route feature: Live video.
     * <p>
     * A route that supports live video routing will allow a mirrored version
     * of the device's primary display or a customized
     * {@link android.app.Presentation Presentation} to be sent to supported
     * destinations.
     * </p><p>
     * When a live video route is selected, audio and video routing is transparent
     * to the application.  By default, audio and video is routed to the selected
     * destination.  For certain live video routes, the application may also use a
     * {@link android.app.Presentation Presentation} to replace the mirrored view
     * on the external display with different content.
     * </p><p>
     * Refer to the class documentation for details about live video routes.
     * </p>
     *
     * @see android.app.Presentation
     */
    public static final String FEATURE_LIVE_VIDEO = "android.media.route.feature.LIVE_VIDEO";

    /**
     * Route feature: Local playback.
     * @hide
     */
    public static final String FEATURE_LOCAL_PLAYBACK =
            "android.media.route.feature.LOCAL_PLAYBACK";

    /**
     * Route feature: Remote playback.
     * <p>
     * A route that supports remote playback routing will allow an application to send
     * requests to play content remotely to supported destinations.
     * A route may only support {@link #FEATURE_REMOTE_AUDIO_PLAYBACK audio playback} or
     * {@link #FEATURE_REMOTE_VIDEO_PLAYBACK video playback}.
     * </p><p>
     * Remote playback routes destinations operate independently of the local device.
     * When a remote playback route is selected, the application can control the content
     * playing on the destination using {@link MediaRouter2.RoutingController#getControlHints()}.
     * The application may also receive status updates from the route regarding remote playback.
     * </p><p>
     * Refer to the class documentation for details about remote playback routes.
     * </p>
     * @see #FEATURE_REMOTE_AUDIO_PLAYBACK
     * @see #FEATURE_REMOTE_VIDEO_PLAYBACK
     */
    public static final String FEATURE_REMOTE_PLAYBACK =
            "android.media.route.feature.REMOTE_PLAYBACK";

    /**
     * Route feature: Remote audio playback.
     * <p>
     * A route that supports remote audio playback routing will allow an application to send
     * requests to play audio content remotely to supported destinations.
     *
     * @see #FEATURE_REMOTE_PLAYBACK
     * @see #FEATURE_REMOTE_VIDEO_PLAYBACK
     */
    public static final String FEATURE_REMOTE_AUDIO_PLAYBACK =
            "android.media.route.feature.REMOTE_AUDIO_PLAYBACK";

    /**
     * Route feature: Remote video playback.
     * <p>
     * A route that supports remote video playback routing will allow an application to send
     * requests to play video content remotely to supported destinations.
     *
     * @see #FEATURE_REMOTE_PLAYBACK
     * @see #FEATURE_REMOTE_AUDIO_PLAYBACK
     */
    public static final String FEATURE_REMOTE_VIDEO_PLAYBACK =
            "android.media.route.feature.REMOTE_VIDEO_PLAYBACK";

    /**
     * Route feature: Remote group playback.
     * <p>
     * @hide
     */
    public static final String FEATURE_REMOTE_GROUP_PLAYBACK =
            "android.media.route.feature.REMOTE_GROUP_PLAYBACK";

    final String mId;
    final CharSequence mName;
    final List<String> mFeatures;
    @Type
    final int mType;
    final boolean mIsSystem;
    final Uri mIconUri;
    final CharSequence mDescription;
    @ConnectionState
    final int mConnectionState;
    final String mClientPackageName;
    final int mVolumeHandling;
    final int mVolumeMax;
    final int mVolume;
    final String mAddress;
    final Bundle mExtras;
    final String mProviderId;

    MediaRoute2Info(@NonNull Builder builder) {
        mId = builder.mId;
        mName = builder.mName;
        mFeatures = builder.mFeatures;
        mType = builder.mType;
        mIsSystem = builder.mIsSystem;
        mIconUri = builder.mIconUri;
        mDescription = builder.mDescription;
        mConnectionState = builder.mConnectionState;
        mClientPackageName = builder.mClientPackageName;
        mVolumeHandling = builder.mVolumeHandling;
        mVolumeMax = builder.mVolumeMax;
        mVolume = builder.mVolume;
        mAddress = builder.mAddress;
        mExtras = builder.mExtras;
        mProviderId = builder.mProviderId;
    }

    MediaRoute2Info(@NonNull Parcel in) {
        mId = in.readString();
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mFeatures = in.createStringArrayList();
        mType = in.readInt();
        mIsSystem = in.readBoolean();
        mIconUri = in.readParcelable(null);
        mDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mConnectionState = in.readInt();
        mClientPackageName = in.readString();
        mVolumeHandling = in.readInt();
        mVolumeMax = in.readInt();
        mVolume = in.readInt();
        mAddress = in.readString();
        mExtras = in.readBundle();
        mProviderId = in.readString();
    }

    /**
     * Gets the id of the route. The routes which are given by {@link MediaRouter2} will have
     * unique IDs.
     * <p>
     * In order to ensure uniqueness in {@link MediaRouter2} side, the value of this method
     * can be different from what was set in {@link MediaRoute2ProviderService}.
     *
     * @see Builder#Builder(String, CharSequence)
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
     * Gets the user-visible name of the route.
     */
    @NonNull
    public CharSequence getName() {
        return mName;
    }

    /**
     * Gets the supported features of the route.
     */
    @NonNull
    public List<String> getFeatures() {
        return mFeatures;
    }

    /**
     * Gets the type of this route.
     *
     * @return The type of this route:
     * {@link #TYPE_UNKNOWN},
     * {@link #TYPE_BUILTIN_SPEAKER}, {@link #TYPE_WIRED_HEADSET}, {@link #TYPE_WIRED_HEADPHONES},
     * {@link #TYPE_BLUETOOTH_A2DP}, {@link #TYPE_HDMI}, {@link #TYPE_DOCK},
     * {@Link #TYPE_USB_DEVICE}, {@link #TYPE_USB_ACCESSORY}, {@link #TYPE_USB_HEADSET}
     * {@link #TYPE_HEARING_AID},
     * {@link #TYPE_REMOTE_TV}, {@link #TYPE_REMOTE_SPEAKER}, {@link #TYPE_GROUP}.
     * @hide
     */
    @Type
    public int getType() {
        return mType;
    }

    /**
     * Returns whether the route is a system route or not.
     * <p>
     * System routes are media routes directly controlled by the system
     * such as phone speaker, wired headset, and Bluetooth devices.
     * </p>
     */
    public boolean isSystemRoute() {
        return mIsSystem;
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
     * Gets the user-visible description of the route.
     */
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
     * Gets the package name of the app using the route.
     * Returns null if no apps are using this route.
     */
    @Nullable
    public String getClientPackageName() {
        return mClientPackageName;
    }

    /**
     * Gets information about how volume is handled on the route.
     *
     * @return {@link #PLAYBACK_VOLUME_FIXED} or {@link #PLAYBACK_VOLUME_VARIABLE}
     */
    @PlaybackVolume
    public int getVolumeHandling() {
        return mVolumeHandling;
    }

    /**
     * Gets the maximum volume of the route.
     */
    public int getVolumeMax() {
        return mVolumeMax;
    }

    /**
     * Gets the current volume of the route. This may be invalid if the route is not selected.
     */
    public int getVolume() {
        return mVolume;
    }

    /**
     * Gets the hardware address of the route if available.
     * @hide
     */
    @Nullable
    public String getAddress() {
        return mAddress;
    }

    @Nullable
    public Bundle getExtras() {
        return mExtras == null ? null : new Bundle(mExtras);
    }

    /**
     * Gets the original id set by {@link Builder#Builder(String, CharSequence)}.
     * @hide
     */
    @NonNull
    @TestApi
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

    /**
     * Returns if the route has at least one of the specified route features.
     *
     * @param features the list of route features to consider
     * @return true if the route has at least one feature in the list
     * @hide
     */
    public boolean hasAnyFeatures(@NonNull Collection<String> features) {
        Objects.requireNonNull(features, "features must not be null");
        for (String feature : features) {
            if (getFeatures().contains(feature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the route info has all of the required field.
     * A route is valid if and only if it is obtained from
     * {@link com.android.server.media.MediaRouterService}.
     * @hide
     */
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

        // Note: mExtras is not included.
        return Objects.equals(mId, other.mId)
                && Objects.equals(mName, other.mName)
                && Objects.equals(mFeatures, other.mFeatures)
                && (mType == other.mType)
                && (mIsSystem == other.mIsSystem)
                && Objects.equals(mIconUri, other.mIconUri)
                && Objects.equals(mDescription, other.mDescription)
                && (mConnectionState == other.mConnectionState)
                && Objects.equals(mClientPackageName, other.mClientPackageName)
                && (mVolumeHandling == other.mVolumeHandling)
                && (mVolumeMax == other.mVolumeMax)
                && (mVolume == other.mVolume)
                && Objects.equals(mAddress, other.mAddress)
                && Objects.equals(mProviderId, other.mProviderId);
    }

    @Override
    public int hashCode() {
        // Note: mExtras is not included.
        return Objects.hash(mId, mName, mFeatures, mType, mIsSystem, mIconUri, mDescription,
                mConnectionState, mClientPackageName, mVolumeHandling, mVolumeMax, mVolume,
                mAddress, mProviderId);
    }

    @Override
    public String toString() {
        // Note: mExtras is not printed here.
        StringBuilder result = new StringBuilder()
                .append("MediaRoute2Info{ ")
                .append("id=").append(getId())
                .append(", name=").append(getName())
                .append(", features=").append(getFeatures())
                .append(", iconUri=").append(getIconUri())
                .append(", description=").append(getDescription())
                .append(", connectionState=").append(getConnectionState())
                .append(", clientPackageName=").append(getClientPackageName())
                .append(", volumeHandling=").append(getVolumeHandling())
                .append(", volumeMax=").append(getVolumeMax())
                .append(", volume=").append(getVolume())
                .append(", providerId=").append(getProviderId())
                .append(" }");
        return result.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        TextUtils.writeToParcel(mName, dest, flags);
        dest.writeStringList(mFeatures);
        dest.writeInt(mType);
        dest.writeBoolean(mIsSystem);
        dest.writeParcelable(mIconUri, flags);
        TextUtils.writeToParcel(mDescription, dest, flags);
        dest.writeInt(mConnectionState);
        dest.writeString(mClientPackageName);
        dest.writeInt(mVolumeHandling);
        dest.writeInt(mVolumeMax);
        dest.writeInt(mVolume);
        dest.writeString(mAddress);
        dest.writeBundle(mExtras);
        dest.writeString(mProviderId);
    }

    /**
     * Builder for {@link MediaRoute2Info media route info}.
     */
    public static final class Builder {
        final String mId;
        final CharSequence mName;
        final List<String> mFeatures;

        @Type
        int mType = TYPE_UNKNOWN;
        boolean mIsSystem;
        Uri mIconUri;
        CharSequence mDescription;
        @ConnectionState
        int mConnectionState;
        String mClientPackageName;
        int mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        int mVolumeMax;
        int mVolume;
        String mAddress;
        Bundle mExtras;
        String mProviderId;

        /**
         * Constructor for builder to create {@link MediaRoute2Info}.
         * <p>
         * In order to ensure ID uniqueness, the {@link MediaRoute2Info#getId() ID} of a route info
         * obtained from {@link MediaRouter2} can be different from what was set in
         * {@link MediaRoute2ProviderService}.
         * </p>
         * @param id The ID of the route. Must not be empty.
         * @param name The user-visible name of the route.
         */
        public Builder(@NonNull String id, @NonNull CharSequence name) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id must not be empty");
            }
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name must not be empty");
            }
            mId = id;
            mName = name;
            mFeatures = new ArrayList<>();
        }

        /**
         * Constructor for builder to create {@link MediaRoute2Info} with existing
         * {@link MediaRoute2Info} instance.
         *
         * @param routeInfo the existing instance to copy data from.
         */
        public Builder(@NonNull MediaRoute2Info routeInfo) {
            this(routeInfo.mId, routeInfo);
        }

        /**
         * Constructor for builder to create {@link MediaRoute2Info} with existing
         * {@link MediaRoute2Info} instance and replace ID with the given {@code id}.
         *
         * @param id The ID of the new route. Must not be empty.
         * @param routeInfo the existing instance to copy data from.
         * @hide
         */
        public Builder(@NonNull String id, @NonNull MediaRoute2Info routeInfo) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id must not be empty");
            }
            Objects.requireNonNull(routeInfo, "routeInfo must not be null");

            mId = id;
            mName = routeInfo.mName;
            mFeatures = new ArrayList<>(routeInfo.mFeatures);
            mType = routeInfo.mType;
            mIsSystem = routeInfo.mIsSystem;
            mIconUri = routeInfo.mIconUri;
            mDescription = routeInfo.mDescription;
            mConnectionState = routeInfo.mConnectionState;
            mClientPackageName = routeInfo.mClientPackageName;
            mVolumeHandling = routeInfo.mVolumeHandling;
            mVolumeMax = routeInfo.mVolumeMax;
            mVolume = routeInfo.mVolume;
            mAddress = routeInfo.mAddress;
            if (routeInfo.mExtras != null) {
                mExtras = new Bundle(routeInfo.mExtras);
            }
            mProviderId = routeInfo.mProviderId;
        }

        /**
         * Adds a feature for the route.
         * @param feature a feature that the route has. May be one of predefined features
         *                such as {@link #FEATURE_LIVE_AUDIO}, {@link #FEATURE_LIVE_VIDEO} or
         *                {@link #FEATURE_REMOTE_PLAYBACK} or a custom feature defined by
         *                a provider.
         *
         * @see #addFeatures(Collection)
         */
        @NonNull
        public Builder addFeature(@NonNull String feature) {
            if (TextUtils.isEmpty(feature)) {
                throw new IllegalArgumentException("feature must not be null or empty");
            }
            mFeatures.add(feature);
            return this;
        }

        /**
         * Adds features for the route. A route must support at least one route type.
         * @param features features that the route has. May include predefined features
         *                such as {@link #FEATURE_LIVE_AUDIO}, {@link #FEATURE_LIVE_VIDEO} or
         *                {@link #FEATURE_REMOTE_PLAYBACK} or custom features defined by
         *                a provider.
         *
         * @see #addFeature(String)
         */
        @NonNull
        public Builder addFeatures(@NonNull Collection<String> features) {
            Objects.requireNonNull(features, "features must not be null");
            for (String feature : features) {
                addFeature(feature);
            }
            return this;
        }

        /**
         * Clears the features of the route. A route must support at least one route type.
         */
        @NonNull
        public Builder clearFeatures() {
            mFeatures.clear();
            return this;
        }

        /**
         * Sets the route's type.
         * @hide
         */
        @NonNull
        public Builder setType(@Type int type) {
            mType = type;
            return this;
        }

        /**
         * Sets whether the route is a system route or not.
         * @hide
         */
        @NonNull
        public Builder setSystemRoute(boolean isSystem) {
            mIsSystem = isSystem;
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
         * Sets the user-visible description of the route.
         */
        @NonNull
        public Builder setDescription(@Nullable CharSequence description) {
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
         * Sets the package name of the app using the route.
         */
        @NonNull
        public Builder setClientPackageName(@Nullable String packageName) {
            mClientPackageName = packageName;
            return this;
        }

        /**
         * Sets the route's volume handling.
         */
        @NonNull
        public Builder setVolumeHandling(@PlaybackVolume int volumeHandling) {
            mVolumeHandling = volumeHandling;
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
         * Sets the route's current volume, or 0 if unknown.
         */
        @NonNull
        public Builder setVolume(int volume) {
            mVolume = volume;
            return this;
        }

        /**
         * Sets the hardware address of the route.
         * @hide
         */
        @NonNull
        public Builder setAddress(String address) {
            mAddress = address;
            return this;
        }

        /**
         * Sets a bundle of extras for the route.
         * <p>
         * Note: The extras will not affect the result of {@link MediaRoute2Info#equals(Object)}.
         */
        @NonNull
        public Builder setExtras(@Nullable Bundle extras) {
            if (extras == null) {
                mExtras = null;
                return this;
            }
            mExtras = new Bundle(extras);
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
         * Builds the {@link MediaRoute2Info media route info}.
         *
         * @throws IllegalArgumentException if no features are added.
         */
        @NonNull
        public MediaRoute2Info build() {
            if (mFeatures.isEmpty()) {
                throw new IllegalArgumentException("features must not be empty!");
            }
            return new MediaRoute2Info(this);
        }
    }
}
