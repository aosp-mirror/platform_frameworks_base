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

    /**
     * Playback information indicating the playback volume is fixed, i&#46;e&#46; it cannot be
     * controlled from this object. An example of fixed playback volume is a remote player,
     * playing over HDMI where the user prefers to control the volume on the HDMI sink, rather
     * than attenuate at the source.
     * @see #getVolumeHandling()
     */
    public static final int PLAYBACK_VOLUME_FIXED = 0;
    /**
     * Playback information indicating the playback volume is variable and can be controlled
     * from this object.
     * @see #getVolumeHandling()
     */
    public static final int PLAYBACK_VOLUME_VARIABLE = 1;

    @NonNull
    final String mId;
    @Nullable
    final String mProviderId;
    @NonNull
    final String mName;
    @Nullable
    final String mDescription;
    @Nullable
    final String mClientPackageName;
    @NonNull
    final List<String> mSupportedCategories;
    final int mVolume;
    final int mVolumeMax;
    final int mVolumeHandling;
    @Nullable
    final Bundle mExtras;

    private final String mUniqueId;

    MediaRoute2Info(@NonNull Builder builder) {
        mId = builder.mId;
        mProviderId = builder.mProviderId;
        mName = builder.mName;
        mDescription = builder.mDescription;
        mClientPackageName = builder.mClientPackageName;
        mSupportedCategories = builder.mSupportedCategories;
        mVolume = builder.mVolume;
        mVolumeMax = builder.mVolumeMax;
        mVolumeHandling = builder.mVolumeHandling;
        mExtras = builder.mExtras;
        mUniqueId = createUniqueId();
    }

    MediaRoute2Info(@NonNull Parcel in) {
        mId = in.readString();
        mProviderId = in.readString();
        mName = in.readString();
        mDescription = in.readString();
        mClientPackageName = in.readString();
        mSupportedCategories = in.createStringArrayList();
        mVolume = in.readInt();
        mVolumeMax = in.readInt();
        mVolumeHandling = in.readInt();
        mExtras = in.readBundle();
        mUniqueId = createUniqueId();
    }

    private String createUniqueId() {
        String uniqueId = null;
        if (mProviderId != null) {
            uniqueId = mProviderId + ":" + mId;
        }
        return uniqueId;
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
                && Objects.equals(mClientPackageName, other.mClientPackageName)
                && Objects.equals(mSupportedCategories, other.mSupportedCategories)
                && (mVolume == other.mVolume)
                && (mVolumeMax == other.mVolumeMax)
                && (mVolumeHandling == other.mVolumeHandling)
                //TODO: This will be evaluated as false in most cases. Try not to.
                && Objects.equals(mExtras, other.mExtras);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mName, mDescription, mSupportedCategories);
    }

    /**
     * Gets the id of the route.
     * Use {@link #getUniqueId()} if you need a unique identifier.
     *
     * @see #getUniqueId()
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Gets the unique id of the route. A route obtained from
     * {@link com.android.server.media.MediaRouterService} always has a unique id.
     *
     * @return unique id of the route or null if it has no unique id.
     */
    @Nullable
    public String getUniqueId() {
        return mUniqueId;
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
    public String getName() {
        return mName;
    }

    @Nullable
    public String getDescription() {
        return mDescription;
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
    public List<String> getSupportedCategories() {
        return mSupportedCategories;
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

    //TODO: Move this if we re-define control category / selector things.
    /**
     * Returns true if the route supports at least one of the specified control categories
     *
     * @param controlCategories the list of control categories to consider
     * @return true if the route supports at least one category
     */
    public boolean supportsControlCategory(@NonNull Collection<String> controlCategories) {
        Objects.requireNonNull(controlCategories, "control categories must not be null");
        for (String controlCategory : controlCategories) {
            for (String supportedCategory : getSupportedCategories()) {
                if (TextUtils.equals(controlCategory, supportedCategory)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mProviderId);
        dest.writeString(mName);
        dest.writeString(mDescription);
        dest.writeString(mClientPackageName);
        dest.writeStringList(mSupportedCategories);
        dest.writeInt(mVolume);
        dest.writeInt(mVolumeMax);
        dest.writeInt(mVolumeHandling);
        dest.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("MediaRouteInfo{ ")
                .append("id=").append(getId())
                .append(", name=").append(getName())
                .append(", description=").append(getDescription())
                .append(", volume=").append(getVolume())
                .append(", volumeMax=").append(getVolumeMax())
                .append(", volumeHandling=").append(getVolumeHandling())
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
        String mName;
        String mDescription;
        String mClientPackageName;
        List<String> mSupportedCategories;
        int mVolume;
        int mVolumeMax;
        int mVolumeHandling;
        Bundle mExtras;

        public Builder(@NonNull String id, @NonNull String name) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("id must not be null or empty");
            }
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name must not be null or empty");
            }
            setId(id);
            setName(name);
            mSupportedCategories = new ArrayList<>();
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
            setClientPackageName(routeInfo.mClientPackageName);
            setSupportedCategories(routeInfo.mSupportedCategories);
            setVolume(routeInfo.mVolume);
            setVolumeMax(routeInfo.mVolumeMax);
            setVolumeHandling(routeInfo.mVolumeHandling);
            if (routeInfo.mExtras != null) {
                mExtras = new Bundle(routeInfo.mExtras);
            }
        }

        /**
         * Sets the unique id of the route.
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
        public Builder setName(@NonNull String name) {
            if (TextUtils.isEmpty(name)) {
                throw new IllegalArgumentException("name must not be null or empty");
            }
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
         * Sets the package name of the app using the route.
         */
        @NonNull
        public Builder setClientPackageName(@Nullable String packageName) {
            mClientPackageName = packageName;
            return this;
        }

        /**
         * Sets the supported categories of the route.
         */
        @NonNull
        public Builder setSupportedCategories(@NonNull Collection<String> categories) {
            mSupportedCategories = new ArrayList<>();
            return addSupportedCategories(categories);
        }

        /**
         * Adds supported categories for the route.
         */
        @NonNull
        public Builder addSupportedCategories(@NonNull Collection<String> categories) {
            Objects.requireNonNull(categories, "categories must not be null");
            for (String category: categories) {
                addSupportedCategory(category);
            }
            return this;
        }

        /**
         * Add a supported category for the route.
         */
        @NonNull
        public Builder addSupportedCategory(@NonNull String category) {
            if (TextUtils.isEmpty(category)) {
                throw new IllegalArgumentException("category must not be null or empty");
            }
            mSupportedCategories.add(category);
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
