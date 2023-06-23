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

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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

    /**
     * The {@link #getOriginalId() original id} of the route that represents the built-in media
     * route.
     *
     * <p>A route with this id will only be visible to apps with permission to do system routing,
     * which means having {@link android.Manifest.permission#BLUETOOTH_CONNECT} and {@link
     * android.Manifest.permission#BLUETOOTH_SCAN}, or {@link
     * android.Manifest.permission#MODIFY_AUDIO_ROUTING}.
     *
     * @hide
     */
    public static final String ROUTE_ID_DEVICE = "DEVICE_ROUTE";

    /**
     * The {@link #getOriginalId() original id} of the route that represents the default system
     * media route.
     *
     * <p>A route with this id will be visible to apps with no permission over system routing. See
     * {@link #ROUTE_ID_DEVICE} for details.
     *
     * @hide
     */
    public static final String ROUTE_ID_DEFAULT = "DEFAULT_ROUTE";

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
    @IntDef(
            prefix = {"TYPE_"},
            value = {
                TYPE_UNKNOWN,
                TYPE_BUILTIN_SPEAKER,
                TYPE_WIRED_HEADSET,
                TYPE_WIRED_HEADPHONES,
                TYPE_BLUETOOTH_A2DP,
                TYPE_HDMI,
                TYPE_USB_DEVICE,
                TYPE_USB_ACCESSORY,
                TYPE_DOCK,
                TYPE_USB_HEADSET,
                TYPE_HEARING_AID,
                TYPE_BLE_HEADSET,
                TYPE_REMOTE_TV,
                TYPE_REMOTE_SPEAKER,
                TYPE_REMOTE_AUDIO_VIDEO_RECEIVER,
                TYPE_REMOTE_TABLET,
                TYPE_REMOTE_TABLET_DOCKED,
                TYPE_REMOTE_COMPUTER,
                TYPE_REMOTE_GAME_CONSOLE,
                TYPE_REMOTE_CAR,
                TYPE_REMOTE_SMARTWATCH,
                TYPE_REMOTE_SMARTPHONE,
                TYPE_GROUP
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Indicates the route's type is unknown or undefined.
     *
     * @see #getType
     */
    public static final int TYPE_UNKNOWN = 0;

    /**
     * Indicates the route is the speaker system (i.e. a mono speaker or stereo speakers) built into
     * the device.
     *
     * @see #getType
     */
    public static final int TYPE_BUILTIN_SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;

    /**
     * Indicates the route is a headset, which is the combination of a headphones and a microphone.
     *
     * @see #getType
     */
    public static final int TYPE_WIRED_HEADSET = AudioDeviceInfo.TYPE_WIRED_HEADSET;

    /**
     * Indicates the route is a pair of wired headphones.
     *
     * @see #getType
     */
    public static final int TYPE_WIRED_HEADPHONES = AudioDeviceInfo.TYPE_WIRED_HEADPHONES;

    /**
     * Indicates the route is a bluetooth device, such as a bluetooth speaker or headphones.
     *
     * @see #getType
     */
    public static final int TYPE_BLUETOOTH_A2DP = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;

    /**
     * Indicates the route is an HDMI connection.
     *
     * @see #getType
     */
    public static final int TYPE_HDMI = AudioDeviceInfo.TYPE_HDMI;

    /**
     * Indicates the route is a USB audio device.
     *
     * @see #getType
     */
    public static final int TYPE_USB_DEVICE = AudioDeviceInfo.TYPE_USB_DEVICE;

    /**
     * Indicates the route is a USB audio device in accessory mode.
     *
     * @see #getType
     */
    public static final int TYPE_USB_ACCESSORY = AudioDeviceInfo.TYPE_USB_ACCESSORY;

    /**
     * Indicates the route is the audio device associated with a dock.
     *
     * @see #getType
     */
    public static final int TYPE_DOCK = AudioDeviceInfo.TYPE_DOCK;

    /**
     * Indicates the route is a USB audio headset.
     *
     * @see #getType
     */
    public static final int TYPE_USB_HEADSET = AudioDeviceInfo.TYPE_USB_HEADSET;

    /**
     * Indicates the route is a hearing aid.
     *
     * @see #getType
     */
    public static final int TYPE_HEARING_AID = AudioDeviceInfo.TYPE_HEARING_AID;

    /**
     * Indicates the route is a Bluetooth Low Energy (BLE) HEADSET.
     *
     * @see #getType
     */
    public static final int TYPE_BLE_HEADSET = AudioDeviceInfo.TYPE_BLE_HEADSET;

    /**
     * Indicates the route is a remote TV.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_TV = 1001;

    /**
     * Indicates the route is a remote speaker.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_SPEAKER = 1002;

    /**
     * Indicates the route is a remote Audio/Video Receiver (AVR).
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_AUDIO_VIDEO_RECEIVER = 1003;

    /**
     * Indicates the route is a remote tablet.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_TABLET = 1004;

    /**
     * Indicates the route is a remote docked tablet.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_TABLET_DOCKED = 1005;

    /**
     * Indicates the route is a remote computer.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_COMPUTER = 1006;

    /**
     * Indicates the route is a remote gaming console.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_GAME_CONSOLE = 1007;

    /**
     * Indicates the route is a remote car.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_CAR = 1008;

    /**
     * Indicates the route is a remote smartwatch.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_SMARTWATCH = 1009;

    /**
     * Indicates the route is a remote smartphone.
     *
     * <p>A remote device uses a routing protocol managed by the application, as opposed to the
     * routing being done by the system.
     *
     * @see #getType
     */
    public static final int TYPE_REMOTE_SMARTPHONE = 1010;

    /**
     * Indicates the route is a group of devices.
     *
     * @see #getType
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
    final String mPackageName;
    final int mVolumeHandling;
    final int mVolumeMax;
    final int mVolume;
    final String mAddress;
    final Set<String> mDeduplicationIds;
    final Bundle mExtras;
    final String mProviderId;
    final boolean mIsVisibilityRestricted;
    final Set<String> mAllowedPackages;

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
        mPackageName = builder.mPackageName;
        mVolumeHandling = builder.mVolumeHandling;
        mVolumeMax = builder.mVolumeMax;
        mVolume = builder.mVolume;
        mAddress = builder.mAddress;
        mDeduplicationIds = builder.mDeduplicationIds;
        mExtras = builder.mExtras;
        mProviderId = builder.mProviderId;
        mIsVisibilityRestricted = builder.mIsVisibilityRestricted;
        mAllowedPackages = builder.mAllowedPackages;
    }

    MediaRoute2Info(@NonNull Parcel in) {
        mId = in.readString();
        Preconditions.checkArgument(!TextUtils.isEmpty(mId));
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mFeatures = in.createStringArrayList();
        mType = in.readInt();
        mIsSystem = in.readBoolean();
        mIconUri = in.readParcelable(null, android.net.Uri.class);
        mDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mConnectionState = in.readInt();
        mClientPackageName = in.readString();
        mPackageName = in.readString();
        mVolumeHandling = in.readInt();
        mVolumeMax = in.readInt();
        mVolume = in.readInt();
        mAddress = in.readString();
        mDeduplicationIds = Set.of(in.readStringArray());
        mExtras = in.readBundle();
        mProviderId = in.readString();
        mIsVisibilityRestricted = in.readBoolean();
        mAllowedPackages = Set.of(in.createString8Array());
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
        if (!TextUtils.isEmpty(mProviderId)) {
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
     * Returns the type of this route.
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
     * Gets the package name of the provider that published the route.
     * <p>
     * It is set by the system service.
     * @hide
     */
    @Nullable
    public String getPackageName() {
        return mPackageName;
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

    /**
     * Gets the deduplication IDs associated to the route.
     *
     * <p>Two routes with a matching deduplication ID originate from the same receiver device.
     */
    @NonNull
    public Set<String> getDeduplicationIds() {
        return mDeduplicationIds;
    }

    /**
     * Gets an optional bundle with extra data.
     */
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
     * @return {@code true} if the route has at least one feature in the list
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
     * Returns if the route has all the specified route features.
     *
     * @hide
     */
    public boolean hasAllFeatures(@NonNull Collection<String> features) {
        Objects.requireNonNull(features, "features must not be null");
        for (String feature : features) {
            if (!getFeatures().contains(feature)) {
                return false;
            }
        }
        return true;
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

    /**
     * Returns whether this route is visible to the package with the given name.
     * @hide
     */
    public boolean isVisibleTo(String packageName) {
        return !mIsVisibilityRestricted || getPackageName().equals(packageName)
                || mAllowedPackages.contains(packageName);
    }

    /**
     * Dumps the current state of the object to the given {@code pw} as a human-readable string.
     *
     * <p> Used in the context of dumpsys. </p>
     *
     * @hide
     */
    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println(prefix + "MediaRoute2Info");

        String indent = prefix + "  ";

        pw.println(indent + "mId=" + mId);
        pw.println(indent + "mName=" + mName);
        pw.println(indent + "mFeatures=" + mFeatures);
        pw.println(indent + "mType=" + getDeviceTypeString(mType));
        pw.println(indent + "mIsSystem=" + mIsSystem);
        pw.println(indent + "mIconUri=" + mIconUri);
        pw.println(indent + "mDescription=" + mDescription);
        pw.println(indent + "mConnectionState=" + mConnectionState);
        pw.println(indent + "mClientPackageName=" + mClientPackageName);
        pw.println(indent + "mPackageName=" + mPackageName);

        dumpVolume(pw, indent);

        pw.println(indent + "mAddress=" + mAddress);
        pw.println(indent + "mDeduplicationIds=" + mDeduplicationIds);
        pw.println(indent + "mExtras=" + mExtras);
        pw.println(indent + "mProviderId=" + mProviderId);
        pw.println(indent + "mIsVisibilityRestricted=" + mIsVisibilityRestricted);
        pw.println(indent + "mAllowedPackages=" + mAllowedPackages);
    }

    private void dumpVolume(@NonNull PrintWriter pw, @NonNull String prefix) {
        String volumeHandlingName;

        switch (mVolumeHandling) {
            case PLAYBACK_VOLUME_FIXED:
                volumeHandlingName = "FIXED";
                break;
            case PLAYBACK_VOLUME_VARIABLE:
                volumeHandlingName = "VARIABLE";
                break;
            default:
                volumeHandlingName = "UNKNOWN";
                break;
        }

        String volume = String.format(Locale.US,
                "volume(current=%d, max=%d, handling=%s(%d))",
                mVolume, mVolumeMax, volumeHandlingName, mVolumeHandling);

        pw.println(prefix + volume);
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
                && Objects.equals(mPackageName, other.mPackageName)
                && (mVolumeHandling == other.mVolumeHandling)
                && (mVolumeMax == other.mVolumeMax)
                && (mVolume == other.mVolume)
                && Objects.equals(mAddress, other.mAddress)
                && Objects.equals(mDeduplicationIds, other.mDeduplicationIds)
                && Objects.equals(mProviderId, other.mProviderId)
                && (mIsVisibilityRestricted == other.mIsVisibilityRestricted)
                && Objects.equals(mAllowedPackages, other.mAllowedPackages);
    }

    @Override
    public int hashCode() {
        // Note: mExtras is not included.
        return Objects.hash(mId, mName, mFeatures, mType, mIsSystem, mIconUri, mDescription,
                mConnectionState, mClientPackageName, mPackageName, mVolumeHandling, mVolumeMax,
                mVolume, mAddress, mDeduplicationIds, mProviderId, mIsVisibilityRestricted,
                mAllowedPackages);
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
                .append(", deduplicationIds=").append(String.join(",", getDeduplicationIds()))
                .append(", providerId=").append(getProviderId())
                .append(", isVisibilityRestricted=").append(mIsVisibilityRestricted)
                .append(", allowedPackages=").append(String.join(",", mAllowedPackages))
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
        dest.writeString(mPackageName);
        dest.writeInt(mVolumeHandling);
        dest.writeInt(mVolumeMax);
        dest.writeInt(mVolume);
        dest.writeString(mAddress);
        dest.writeStringArray(mDeduplicationIds.toArray(new String[mDeduplicationIds.size()]));
        dest.writeBundle(mExtras);
        dest.writeString(mProviderId);
        dest.writeBoolean(mIsVisibilityRestricted);
        dest.writeString8Array(mAllowedPackages.toArray(new String[0]));
    }

    private static String getDeviceTypeString(@Type int deviceType) {
        switch (deviceType) {
            case TYPE_BUILTIN_SPEAKER:
                return "BUILTIN_SPEAKER";
            case TYPE_WIRED_HEADSET:
                return "WIRED_HEADSET";
            case TYPE_WIRED_HEADPHONES:
                return "WIRED_HEADPHONES";
            case TYPE_BLUETOOTH_A2DP:
                return "BLUETOOTH_A2DP";
            case TYPE_HDMI:
                return "HDMI";
            case TYPE_DOCK:
                return "DOCK";
            case TYPE_USB_DEVICE:
                return "USB_DEVICE";
            case TYPE_USB_ACCESSORY:
                return "USB_ACCESSORY";
            case TYPE_USB_HEADSET:
                return "USB_HEADSET";
            case TYPE_HEARING_AID:
                return "HEARING_AID";
            case TYPE_REMOTE_TV:
                return "REMOTE_TV";
            case TYPE_REMOTE_SPEAKER:
                return "REMOTE_SPEAKER";
            case TYPE_REMOTE_AUDIO_VIDEO_RECEIVER:
                return "REMOTE_AUDIO_VIDEO_RECEIVER";
            case TYPE_REMOTE_TABLET:
                return "REMOTE_TABLET";
            case TYPE_REMOTE_TABLET_DOCKED:
                return "REMOTE_TABLET_DOCKED";
            case TYPE_REMOTE_COMPUTER:
                return "REMOTE_COMPUTER";
            case TYPE_REMOTE_GAME_CONSOLE:
                return "REMOTE_GAME_CONSOLE";
            case TYPE_REMOTE_CAR:
                return "REMOTE_CAR";
            case TYPE_REMOTE_SMARTWATCH:
                return "REMOTE_SMARTWATCH";
            case TYPE_REMOTE_SMARTPHONE:
                return "REMOTE_SMARTPHONE";
            case TYPE_GROUP:
                return "GROUP";
            case TYPE_UNKNOWN:
            default:
                return TextUtils.formatSimple("UNKNOWN(%d)", deviceType);
        }
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
        String mPackageName;
        int mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        int mVolumeMax;
        int mVolume;
        String mAddress;
        Set<String> mDeduplicationIds;
        Bundle mExtras;
        String mProviderId;
        boolean mIsVisibilityRestricted;
        Set<String> mAllowedPackages;

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
            mDeduplicationIds = Set.of();
            mAllowedPackages = Set.of();
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
            mPackageName = routeInfo.mPackageName;
            mVolumeHandling = routeInfo.mVolumeHandling;
            mVolumeMax = routeInfo.mVolumeMax;
            mVolume = routeInfo.mVolume;
            mAddress = routeInfo.mAddress;
            mDeduplicationIds = Set.copyOf(routeInfo.mDeduplicationIds);
            if (routeInfo.mExtras != null) {
                mExtras = new Bundle(routeInfo.mExtras);
            }
            mProviderId = routeInfo.mProviderId;
            mIsVisibilityRestricted = routeInfo.mIsVisibilityRestricted;
            mAllowedPackages = routeInfo.mAllowedPackages;
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
         *
         * @see MediaRoute2Info#getType()
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
         * Sets the package name of the route.
         * @hide
         */
        @NonNull
        public Builder setPackageName(@NonNull String packageName) {
            mPackageName = packageName;
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
         * Sets the {@link MediaRoute2Info#getDeduplicationIds() deduplication IDs} of the route.
         */
        @NonNull
        public Builder setDeduplicationIds(@NonNull Set<String> id) {
            mDeduplicationIds = Set.copyOf(id);
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
         * Sets the visibility of this route to public.
         *
         * <p>By default, unless you call {@link #setVisibilityRestricted}, the new route will be
         * public.
         *
         * <p>Public routes are visible to any application with a matching {@link
         * RouteDiscoveryPreference#getPreferredFeatures feature}.
         *
         * <p>Calls to this method override previous calls to {@link #setVisibilityPublic} and
         * {@link #setVisibilityRestricted}.
         */
        @NonNull
        public Builder setVisibilityPublic() {
            mIsVisibilityRestricted = false;
            mAllowedPackages = Set.of();
            return this;
        }

        /**
         * Sets the visibility of this route to restricted.
         *
         * <p>Routes with restricted visibility are only visible to its publisher application and
         * applications whose package name is included in the provided {@code allowedPackages} set
         * with a matching {@link RouteDiscoveryPreference#getPreferredFeatures feature}.
         *
         * <p>Calls to this method override previous calls to {@link #setVisibilityPublic} and
         * {@link #setVisibilityRestricted}.
         *
         * @see #setVisibilityPublic
         * @param allowedPackages set of package names which are allowed to see this route.
         */
        @NonNull
        public Builder setVisibilityRestricted(@NonNull Set<String> allowedPackages) {
            mIsVisibilityRestricted = true;
            mAllowedPackages = Set.copyOf(allowedPackages);
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
