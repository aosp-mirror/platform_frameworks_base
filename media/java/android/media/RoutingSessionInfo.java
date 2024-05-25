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

import static com.android.media.flags.Flags.FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Describes a routing session which is created when a media route is selected.
 */
public final class RoutingSessionInfo implements Parcelable {
    @NonNull
    public static final Creator<RoutingSessionInfo> CREATOR =
            new Creator<RoutingSessionInfo>() {
                @Override
                public RoutingSessionInfo createFromParcel(Parcel in) {
                    return new RoutingSessionInfo(in);
                }
                @Override
                public RoutingSessionInfo[] newArray(int size) {
                    return new RoutingSessionInfo[size];
                }
            };

    private static final String TAG = "RoutingSessionInfo";

    private static final String KEY_GROUP_ROUTE = "androidx.mediarouter.media.KEY_GROUP_ROUTE";
    private static final String KEY_VOLUME_HANDLING = "volumeHandling";

    /**
     * Indicates that the transfer happened by the default logic without explicit system's or user's
     * request.
     *
     * <p>For example, an automatically connected Bluetooth device will have this transfer reason.
     */
    @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
    public static final int TRANSFER_REASON_FALLBACK = 0;

    /** Indicates that the transfer happened from within a privileged application. */
    @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
    public static final int TRANSFER_REASON_SYSTEM_REQUEST = 1;

    /** Indicates that the transfer happened from a non-privileged app. */
    @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
    public static final int TRANSFER_REASON_APP = 2;

    /**
     * Indicates the transfer reason.
     *
     * @hide
     */
    @IntDef(value = {TRANSFER_REASON_FALLBACK, TRANSFER_REASON_SYSTEM_REQUEST, TRANSFER_REASON_APP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransferReason {}

    @NonNull
    final String mId;
    @Nullable
    final CharSequence mName;
    @Nullable
    final String mOwnerPackageName;
    @NonNull
    final String mClientPackageName;
    @Nullable
    final String mProviderId;
    @NonNull
    final List<String> mSelectedRoutes;
    @NonNull
    final List<String> mSelectableRoutes;
    @NonNull
    final List<String> mDeselectableRoutes;
    @NonNull
    final List<String> mTransferableRoutes;

    @MediaRoute2Info.PlaybackVolume final int mVolumeHandling;
    final int mVolumeMax;
    final int mVolume;

    @Nullable
    final Bundle mControlHints;
    final boolean mIsSystemSession;

    @TransferReason final int mTransferReason;

    @Nullable final UserHandle mTransferInitiatorUserHandle;
    @Nullable final String mTransferInitiatorPackageName;

    RoutingSessionInfo(@NonNull Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null.");

        mId = builder.mId;
        mName = builder.mName;
        mOwnerPackageName = builder.mOwnerPackageName;
        mClientPackageName = builder.mClientPackageName;
        mProviderId = builder.mProviderId;

        mSelectedRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mSelectedRoutes));
        mSelectableRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mSelectableRoutes));
        mDeselectableRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mDeselectableRoutes));
        mTransferableRoutes = Collections.unmodifiableList(
                convertToUniqueRouteIds(builder.mTransferableRoutes));

        mVolumeMax = builder.mVolumeMax;
        mVolume = builder.mVolume;

        mIsSystemSession = builder.mIsSystemSession;

        boolean volumeAdjustmentForRemoteGroupSessions = Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_volumeAdjustmentForRemoteGroupSessions);
        mVolumeHandling =
                defineVolumeHandling(
                        mIsSystemSession,
                        builder.mVolumeHandling,
                        mSelectedRoutes,
                        volumeAdjustmentForRemoteGroupSessions);

        mControlHints = updateVolumeHandlingInHints(builder.mControlHints, mVolumeHandling);
        mTransferReason = builder.mTransferReason;
        mTransferInitiatorUserHandle = builder.mTransferInitiatorUserHandle;
        mTransferInitiatorPackageName = builder.mTransferInitiatorPackageName;
    }

    RoutingSessionInfo(@NonNull Parcel src) {
        mId = src.readString();
        Preconditions.checkArgument(!TextUtils.isEmpty(mId));

        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(src);
        mOwnerPackageName = src.readString();
        mClientPackageName = ensureString(src.readString());
        mProviderId = src.readString();

        mSelectedRoutes = ensureList(src.createStringArrayList());
        Preconditions.checkArgument(!mSelectedRoutes.isEmpty());

        mSelectableRoutes = ensureList(src.createStringArrayList());
        mDeselectableRoutes = ensureList(src.createStringArrayList());
        mTransferableRoutes = ensureList(src.createStringArrayList());

        mVolumeHandling = src.readInt();
        mVolumeMax = src.readInt();
        mVolume = src.readInt();

        mControlHints = src.readBundle();
        mIsSystemSession = src.readBoolean();
        mTransferReason = src.readInt();
        mTransferInitiatorUserHandle = UserHandle.readFromParcel(src);
        mTransferInitiatorPackageName = src.readString();
    }

    @Nullable
    private static Bundle updateVolumeHandlingInHints(@Nullable Bundle controlHints,
            int volumeHandling) {
        // Workaround to preserve retro-compatibility with androidx.
        // See b/228021646 for more details.
        if (controlHints != null && controlHints.containsKey(KEY_GROUP_ROUTE)) {
            Bundle groupRoute = controlHints.getBundle(KEY_GROUP_ROUTE);

            if (groupRoute != null && groupRoute.containsKey(KEY_VOLUME_HANDLING)
                    && volumeHandling != groupRoute.getInt(KEY_VOLUME_HANDLING)) {
                //Creating copy of controlHints with updated value.
                Bundle newGroupRoute = new Bundle(groupRoute);
                newGroupRoute.putInt(KEY_VOLUME_HANDLING, volumeHandling);
                Bundle newControlHints = new Bundle(controlHints);
                newControlHints.putBundle(KEY_GROUP_ROUTE, newGroupRoute);
                return newControlHints;
            }
        }
        //Return same Bundle.
        return controlHints;
    }

    @MediaRoute2Info.PlaybackVolume
    private static int defineVolumeHandling(
            boolean isSystemSession,
            @MediaRoute2Info.PlaybackVolume int volumeHandling,
            List<String> selectedRoutes,
            boolean volumeAdjustmentForRemoteGroupSessions) {
        if (!isSystemSession
                && !volumeAdjustmentForRemoteGroupSessions
                && selectedRoutes.size() > 1) {
            return MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
        }
        return volumeHandling;
    }

    @NonNull
    private static String ensureString(@Nullable String str) {
        return str != null ? str : "";
    }

    @NonNull
    private static <T> List<T> ensureList(@Nullable List<? extends T> list) {
        if (list != null) {
            return Collections.unmodifiableList(list);
        }
        return Collections.emptyList();
    }

    /**
     * Gets the id of the session. The sessions which are given by {@link MediaRouter2} will have
     * unique IDs.
     * <p>
     * In order to ensure uniqueness in {@link MediaRouter2} side, the value of this method
     * can be different from what was set in {@link MediaRoute2ProviderService}.
     *
     * @see Builder#Builder(String, String)
     */
    @NonNull
    public String getId() {
        if (!TextUtils.isEmpty(mProviderId)) {
            return MediaRouter2Utils.toUniqueId(mProviderId, mId);
        } else {
            return mId;
        }
    }

    /**
     * Gets the user-visible name of the session. It may be {@code null}.
     */
    @Nullable
    public CharSequence getName() {
        return mName;
    }

    /**
     * Gets the original id set by {@link Builder#Builder(String, String)}.
     * @hide
     */
    @NonNull
    public String getOriginalId() {
        return mId;
    }

    /**
     * Gets the package name of the session owner.
     * @hide
     */
    @Nullable
    public String getOwnerPackageName() {
        return mOwnerPackageName;
    }

    /**
     * Gets the client package name of the session
     */
    @NonNull
    public String getClientPackageName() {
        return mClientPackageName;
    }

    /**
     * Gets the provider id of the session.
     * @hide
     */
    @Nullable
    public String getProviderId() {
        return mProviderId;
    }

    /**
     * Gets the list of IDs of selected routes for the session. It shouldn't be empty.
     */
    @NonNull
    public List<String> getSelectedRoutes() {
        return mSelectedRoutes;
    }

    /**
     * Gets the list of IDs of selectable routes for the session.
     */
    @NonNull
    public List<String> getSelectableRoutes() {
        return mSelectableRoutes;
    }

    /**
     * Gets the list of IDs of deselectable routes for the session.
     */
    @NonNull
    public List<String> getDeselectableRoutes() {
        return mDeselectableRoutes;
    }

    /**
     * Gets the list of IDs of transferable routes for the session.
     */
    @NonNull
    public List<String> getTransferableRoutes() {
        return mTransferableRoutes;
    }

    /**
     * Gets the information about how volume is handled on the session.
     *
     * @return {@link MediaRoute2Info#PLAYBACK_VOLUME_FIXED} or
     * {@link MediaRoute2Info#PLAYBACK_VOLUME_VARIABLE}.
     */
    @MediaRoute2Info.PlaybackVolume
    public int getVolumeHandling() {
        return mVolumeHandling;
    }

    /**
     * Gets the maximum volume of the session.
     */
    public int getVolumeMax() {
        return mVolumeMax;
    }

    /**
     * Gets the current volume of the session.
     * <p>
     * When it's available, it represents the volume of routing session, which is a group
     * of selected routes. To get the volume of each route, use {@link MediaRoute2Info#getVolume()}.
     * </p>
     * @see MediaRoute2Info#getVolume()
     */
    public int getVolume() {
        return mVolume;
    }

    /**
     * Gets the control hints
     */
    @Nullable
    public Bundle getControlHints() {
        return mControlHints;
    }

    /**
     * Gets whether this session is in system media route provider.
     * @hide
     */
    @Nullable
    public boolean isSystemSession() {
        return mIsSystemSession;
    }

    /** Returns the transfer reason for this routing session. */
    @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
    @TransferReason
    public int getTransferReason() {
        return mTransferReason;
    }

    /** @hide */
    @Nullable
    public UserHandle getTransferInitiatorUserHandle() {
        return mTransferInitiatorUserHandle;
    }

    /** @hide */
    @Nullable
    public String getTransferInitiatorPackageName() {
        return mTransferInitiatorPackageName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeCharSequence(mName);
        dest.writeString(mOwnerPackageName);
        dest.writeString(mClientPackageName);
        dest.writeString(mProviderId);
        dest.writeStringList(mSelectedRoutes);
        dest.writeStringList(mSelectableRoutes);
        dest.writeStringList(mDeselectableRoutes);
        dest.writeStringList(mTransferableRoutes);
        dest.writeInt(mVolumeHandling);
        dest.writeInt(mVolumeMax);
        dest.writeInt(mVolume);
        dest.writeBundle(mControlHints);
        dest.writeBoolean(mIsSystemSession);
        dest.writeInt(mTransferReason);
        UserHandle.writeToParcel(mTransferInitiatorUserHandle, dest);
        dest.writeString(mTransferInitiatorPackageName);
    }

    /**
     * Dumps current state of the instance. Use with {@code dumpsys}.
     *
     * See {@link android.os.Binder#dump(FileDescriptor, PrintWriter, String[])}.
     *
     * @hide
     */
    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println(prefix + "RoutingSessionInfo");

        String indent = prefix + "  ";

        pw.println(indent + "mId=" + mId);
        pw.println(indent + "mName=" + mName);
        pw.println(indent + "mOwnerPackageName=" + mOwnerPackageName);
        pw.println(indent + "mClientPackageName=" + mClientPackageName);
        pw.println(indent + "mProviderId=" + mProviderId);
        pw.println(indent + "mSelectedRoutes=" + mSelectedRoutes);
        pw.println(indent + "mSelectableRoutes=" + mSelectableRoutes);
        pw.println(indent + "mDeselectableRoutes=" + mDeselectableRoutes);
        pw.println(indent + "mTransferableRoutes=" + mTransferableRoutes);
        pw.println(indent + MediaRoute2Info.getVolumeString(mVolume, mVolumeMax, mVolumeHandling));
        pw.println(indent + "mControlHints=" + mControlHints);
        pw.println(indent + "mIsSystemSession=" + mIsSystemSession);
        pw.println(indent + "mTransferReason=" + mTransferReason);
        pw.println(indent + "mtransferInitiatorUserHandle=" + mTransferInitiatorUserHandle);
        pw.println(indent + "mtransferInitiatorPackageName=" + mTransferInitiatorPackageName);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        }

        if (this == obj) {
            return true;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        RoutingSessionInfo other = (RoutingSessionInfo) obj;
        return Objects.equals(mId, other.mId)
                && Objects.equals(mName, other.mName)
                && Objects.equals(mOwnerPackageName, other.mOwnerPackageName)
                && Objects.equals(mClientPackageName, other.mClientPackageName)
                && Objects.equals(mProviderId, other.mProviderId)
                && Objects.equals(mSelectedRoutes, other.mSelectedRoutes)
                && Objects.equals(mSelectableRoutes, other.mSelectableRoutes)
                && Objects.equals(mDeselectableRoutes, other.mDeselectableRoutes)
                && Objects.equals(mTransferableRoutes, other.mTransferableRoutes)
                && (mVolumeHandling == other.mVolumeHandling)
                && (mVolumeMax == other.mVolumeMax)
                && (mVolume == other.mVolume)
                && (mTransferReason == other.mTransferReason)
                && Objects.equals(mTransferInitiatorUserHandle, other.mTransferInitiatorUserHandle)
                && Objects.equals(
                mTransferInitiatorPackageName, other.mTransferInitiatorPackageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mId,
                mName,
                mOwnerPackageName,
                mClientPackageName,
                mProviderId,
                mSelectedRoutes,
                mSelectableRoutes,
                mDeselectableRoutes,
                mTransferableRoutes,
                mVolumeMax,
                mVolumeHandling,
                mVolume,
                mTransferReason,
                mTransferInitiatorUserHandle,
                mTransferInitiatorPackageName);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("RoutingSessionInfo{ ")
                .append("sessionId=")
                .append(getId())
                .append(", name=")
                .append(getName())
                .append(", clientPackageName=")
                .append(getClientPackageName())
                .append(", selectedRoutes={")
                .append(String.join(",", getSelectedRoutes()))
                .append("}")
                .append(", selectableRoutes={")
                .append(String.join(",", getSelectableRoutes()))
                .append("}")
                .append(", deselectableRoutes={")
                .append(String.join(",", getDeselectableRoutes()))
                .append("}")
                .append(", transferableRoutes={")
                .append(String.join(",", getTransferableRoutes()))
                .append("}")
                .append(", ")
                .append(MediaRoute2Info.getVolumeString(mVolume, mVolumeMax, mVolumeHandling))
                .append(", transferReason=")
                .append(getTransferReason())
                .append(", transferInitiatorUserHandle=")
                .append(getTransferInitiatorUserHandle())
                .append(", transferInitiatorPackageName=")
                .append(getTransferInitiatorPackageName())
                .append(" }")
                .toString();
    }

    /**
     * Provides a new list with unique route IDs if {@link #mProviderId} is set, or the original IDs
     * otherwise.
     *
     * @param routeIds list of route IDs to convert
     * @return new list with unique IDs or original IDs
     */

    @NonNull
    private List<String> convertToUniqueRouteIds(@NonNull List<String> routeIds) {
        Objects.requireNonNull(routeIds, "RouteIds cannot be null.");

        // mProviderId can be null if not set. Return the original list for this case.
        if (TextUtils.isEmpty(mProviderId)) {
            return new ArrayList<>(routeIds);
        }

        List<String> result = new ArrayList<>();
        for (String routeId : routeIds) {
            result.add(MediaRouter2Utils.toUniqueId(mProviderId, routeId));
        }
        return result;
    }

    /**
     * Builder class for {@link RoutingSessionInfo}.
     */
    public static final class Builder {
        @NonNull
        private final String mId;
        @Nullable
        private CharSequence mName;
        @Nullable
        private String mOwnerPackageName;
        @NonNull
        private String mClientPackageName;
        @Nullable
        private String mProviderId;
        @NonNull
        private final List<String> mSelectedRoutes;
        @NonNull
        private final List<String> mSelectableRoutes;
        @NonNull
        private final List<String> mDeselectableRoutes;
        @NonNull
        private final List<String> mTransferableRoutes;
        @MediaRoute2Info.PlaybackVolume
        private int mVolumeHandling = MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
        private int mVolumeMax;
        private int mVolume;
        @Nullable
        private Bundle mControlHints;
        private boolean mIsSystemSession;
        @TransferReason private int mTransferReason = TRANSFER_REASON_FALLBACK;
        @Nullable private UserHandle mTransferInitiatorUserHandle;
        @Nullable private String mTransferInitiatorPackageName;

        /**
         * Constructor for builder to create {@link RoutingSessionInfo}.
         * <p>
         * In order to ensure ID uniqueness in {@link MediaRouter2} side, the value of
         * {@link RoutingSessionInfo#getId()} can be different from what was set in
         * {@link MediaRoute2ProviderService}.
         * </p>
         *
         * @param id ID of the session. Must not be empty.
         * @param clientPackageName package name of the client app which uses this session.
         *                          If is is unknown, then just use an empty string.
         * @see MediaRoute2Info#getId()
         */
        public Builder(@NonNull String id, @NonNull String clientPackageName) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id must not be empty");
            }

            mId = id;
            mClientPackageName =
                    Objects.requireNonNull(clientPackageName, "clientPackageName must not be null");
            mSelectedRoutes = new ArrayList<>();
            mSelectableRoutes = new ArrayList<>();
            mDeselectableRoutes = new ArrayList<>();
            mTransferableRoutes = new ArrayList<>();
        }

        /**
         * Constructor for builder to create {@link RoutingSessionInfo} with
         * existing {@link RoutingSessionInfo} instance.
         *
         * @param sessionInfo the existing instance to copy data from.
         */
        public Builder(@NonNull RoutingSessionInfo sessionInfo) {
            Objects.requireNonNull(sessionInfo, "sessionInfo must not be null");

            mId = sessionInfo.mId;
            mName = sessionInfo.mName;
            mClientPackageName = sessionInfo.mClientPackageName;
            mProviderId = sessionInfo.mProviderId;

            mSelectedRoutes = new ArrayList<>(sessionInfo.mSelectedRoutes);
            mSelectableRoutes = new ArrayList<>(sessionInfo.mSelectableRoutes);
            mDeselectableRoutes = new ArrayList<>(sessionInfo.mDeselectableRoutes);
            mTransferableRoutes = new ArrayList<>(sessionInfo.mTransferableRoutes);

            if (mProviderId != null) {
                // They must have unique IDs.
                mSelectedRoutes.replaceAll(MediaRouter2Utils::getOriginalId);
                mSelectableRoutes.replaceAll(MediaRouter2Utils::getOriginalId);
                mDeselectableRoutes.replaceAll(MediaRouter2Utils::getOriginalId);
                mTransferableRoutes.replaceAll(MediaRouter2Utils::getOriginalId);
            }

            mVolumeHandling = sessionInfo.mVolumeHandling;
            mVolumeMax = sessionInfo.mVolumeMax;
            mVolume = sessionInfo.mVolume;

            mControlHints = sessionInfo.mControlHints;
            mIsSystemSession = sessionInfo.mIsSystemSession;
            mTransferReason = sessionInfo.mTransferReason;
            mTransferInitiatorUserHandle = sessionInfo.mTransferInitiatorUserHandle;
            mTransferInitiatorPackageName = sessionInfo.mTransferInitiatorPackageName;
        }

        /**
         * Sets the user-visible name of the session.
         */
        @NonNull
        public Builder setName(@Nullable CharSequence name) {
            mName = name;
            return this;
        }

        /**
         * Sets the package name of the session owner. It is expected to be called by the system.
         *
         * @hide
         */
        @NonNull
        public Builder setOwnerPackageName(@Nullable String packageName) {
            mOwnerPackageName = packageName;
            return this;
        }

        /**
         * Sets the client package name of the session.
         *
         * @hide
         */
        @NonNull
        public Builder setClientPackageName(@Nullable String packageName) {
            mClientPackageName = packageName;
            return this;
        }

        /**
         * Sets the provider ID of the session.
         *
         * @hide
         */
        @NonNull
        public Builder setProviderId(@NonNull String providerId) {
            if (TextUtils.isEmpty(providerId)) {
                throw new IllegalArgumentException("providerId must not be empty");
            }
            mProviderId = providerId;
            return this;
        }

        /**
         * Clears the selected routes.
         */
        @NonNull
        public Builder clearSelectedRoutes() {
            mSelectedRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the selected routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder addSelectedRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mSelectedRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a route from the selected routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder removeSelectedRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mSelectedRoutes.remove(routeId);
            return this;
        }

        /**
         * Clears the selectable routes.
         */
        @NonNull
        public Builder clearSelectableRoutes() {
            mSelectableRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the selectable routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder addSelectableRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mSelectableRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a route from the selectable routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder removeSelectableRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mSelectableRoutes.remove(routeId);
            return this;
        }

        /**
         * Clears the deselectable routes.
         */
        @NonNull
        public Builder clearDeselectableRoutes() {
            mDeselectableRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the deselectable routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder addDeselectableRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mDeselectableRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a route from the deselectable routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder removeDeselectableRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mDeselectableRoutes.remove(routeId);
            return this;
        }

        /**
         * Clears the transferable routes.
         */
        @NonNull
        public Builder clearTransferableRoutes() {
            mTransferableRoutes.clear();
            return this;
        }

        /**
         * Adds a route to the transferable routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder addTransferableRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mTransferableRoutes.add(routeId);
            return this;
        }

        /**
         * Removes a route from the transferable routes. The {@code routeId} must not be empty.
         */
        @NonNull
        public Builder removeTransferableRoute(@NonNull String routeId) {
            if (TextUtils.isEmpty(routeId)) {
                throw new IllegalArgumentException("routeId must not be empty");
            }
            mTransferableRoutes.remove(routeId);
            return this;
        }

        /**
         * Sets the session's volume handling.
         * {@link MediaRoute2Info#PLAYBACK_VOLUME_FIXED} or
         * {@link MediaRoute2Info#PLAYBACK_VOLUME_VARIABLE}.
         */
        @NonNull
        public RoutingSessionInfo.Builder setVolumeHandling(
                @MediaRoute2Info.PlaybackVolume int volumeHandling) {
            mVolumeHandling = volumeHandling;
            return this;
        }

        /**
         * Sets the session's maximum volume, or 0 if unknown.
         */
        @NonNull
        public RoutingSessionInfo.Builder setVolumeMax(int volumeMax) {
            mVolumeMax = volumeMax;
            return this;
        }

        /**
         * Sets the session's current volume, or 0 if unknown.
         */
        @NonNull
        public RoutingSessionInfo.Builder setVolume(int volume) {
            mVolume = volume;
            return this;
        }

        /**
         * Sets control hints.
         */
        @NonNull
        public Builder setControlHints(@Nullable Bundle controlHints) {
            mControlHints = controlHints;
            return this;
        }

        /**
         * Sets whether this session is in system media route provider.
         * @hide
         */
        @NonNull
        public Builder setSystemSession(boolean isSystemSession) {
            mIsSystemSession = isSystemSession;
            return this;
        }

        /**
         * Sets transfer reason for the current session.
         *
         * <p>By default the transfer reason is set to {@link
         * RoutingSessionInfo#TRANSFER_REASON_FALLBACK}.
         */
        @NonNull
        @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
        public Builder setTransferReason(@TransferReason int transferReason) {
            mTransferReason = transferReason;
            return this;
        }

        /**
         * Sets the user handle and package name of the process that initiated the transfer.
         *
         * <p>By default the transfer initiation user handle and package name are set to {@code
         * null}.
         */
        @NonNull
        @FlaggedApi(FLAG_ENABLE_BUILT_IN_SPEAKER_ROUTE_SUITABILITY_STATUSES)
        public Builder setTransferInitiator(
                @Nullable UserHandle transferInitiatorUserHandle,
                @Nullable String transferInitiatorPackageName) {
            mTransferInitiatorUserHandle = transferInitiatorUserHandle;
            mTransferInitiatorPackageName = transferInitiatorPackageName;
            return this;
        }

        /**
         * Builds a routing session info.
         *
         * @throws IllegalArgumentException if no selected routes are added.
         */
        @NonNull
        public RoutingSessionInfo build() {
            if (mSelectedRoutes.isEmpty()) {
                throw new IllegalArgumentException("selectedRoutes must not be empty");
            }
            return new RoutingSessionInfo(this);
        }
    }
}
