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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;

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

    final String mId;
    final CharSequence mName;
    final String mClientPackageName;
    @Nullable
    final String mProviderId;
    final List<String> mSelectedRoutes;
    final List<String> mSelectableRoutes;
    final List<String> mDeselectableRoutes;
    final List<String> mTransferableRoutes;

    final int mVolumeHandling;
    final int mVolumeMax;
    final int mVolume;

    @Nullable
    final Bundle mControlHints;
    final boolean mIsSystemSession;

    RoutingSessionInfo(@NonNull Builder builder) {
        Objects.requireNonNull(builder, "builder must not be null.");

        mId = builder.mId;
        mName = builder.mName;
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

        mVolumeHandling = builder.mVolumeHandling;
        mVolumeMax = builder.mVolumeMax;
        mVolume = builder.mVolume;

        mControlHints = builder.mControlHints;
        mIsSystemSession = builder.mIsSystemSession;
    }

    RoutingSessionInfo(@NonNull Parcel src) {
        Objects.requireNonNull(src, "src must not be null.");

        mId = ensureString(src.readString());
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(src);
        mClientPackageName = ensureString(src.readString());
        mProviderId = src.readString();

        mSelectedRoutes = ensureList(src.createStringArrayList());
        mSelectableRoutes = ensureList(src.createStringArrayList());
        mDeselectableRoutes = ensureList(src.createStringArrayList());
        mTransferableRoutes = ensureList(src.createStringArrayList());

        mVolumeHandling = src.readInt();
        mVolumeMax = src.readInt();
        mVolume = src.readInt();

        mControlHints = src.readBundle();
        mIsSystemSession = src.readBoolean();
    }

    private static String ensureString(String str) {
        return str != null ? str : "";
    }

    private static <T> List<T> ensureList(List<? extends T> list) {
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
        if (mProviderId != null) {
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
     * Gets information about how volume is handled on the session.
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeCharSequence(mName);
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
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof RoutingSessionInfo)) {
            return false;
        }

        RoutingSessionInfo other = (RoutingSessionInfo) obj;
        return Objects.equals(mId, other.mId)
                && Objects.equals(mName, other.mName)
                && Objects.equals(mClientPackageName, other.mClientPackageName)
                && Objects.equals(mProviderId, other.mProviderId)
                && Objects.equals(mSelectedRoutes, other.mSelectedRoutes)
                && Objects.equals(mSelectableRoutes, other.mSelectableRoutes)
                && Objects.equals(mDeselectableRoutes, other.mDeselectableRoutes)
                && Objects.equals(mTransferableRoutes, other.mTransferableRoutes)
                && (mVolumeHandling == other.mVolumeHandling)
                && (mVolumeMax == other.mVolumeMax)
                && (mVolume == other.mVolume);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mName, mClientPackageName, mProviderId,
                mSelectedRoutes, mSelectableRoutes, mDeselectableRoutes, mTransferableRoutes,
                mVolumeMax, mVolumeHandling, mVolume);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("RoutingSessionInfo{ ")
                .append("sessionId=").append(getId())
                .append(", name=").append(getName())
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
                .append(", volumeHandling=").append(getVolumeHandling())
                .append(", volumeMax=").append(getVolumeMax())
                .append(", volume=").append(getVolume())
                .append(" }");
        return result.toString();
    }

    private List<String> convertToUniqueRouteIds(@NonNull List<String> routeIds) {
        if (routeIds == null) {
            Log.w(TAG, "routeIds is null. Returning an empty list");
            return Collections.emptyList();
        }

        // mProviderId can be null if not set. Return the original list for this case.
        if (mProviderId == null) {
            return routeIds;
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
        // TODO: Reorder these (important ones first)
        final String mId;
        CharSequence mName;
        String mClientPackageName;
        String mProviderId;
        final List<String> mSelectedRoutes;
        final List<String> mSelectableRoutes;
        final List<String> mDeselectableRoutes;
        final List<String> mTransferableRoutes;
        int mVolumeHandling = MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
        int mVolumeMax;
        int mVolume;
        Bundle mControlHints;
        boolean mIsSystemSession;

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
