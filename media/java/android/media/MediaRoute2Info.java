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

    @NonNull
    final String mId;
    @NonNull
    final String mName;
    @Nullable
    final String mDescription;
    @Nullable
    final Bundle mExtras;

    MediaRoute2Info(@NonNull Builder builder) {
        mId = builder.mId;
        mName = builder.mName;
        mDescription = builder.mDescription;
        mExtras = builder.mExtras;
    }

    MediaRoute2Info(@NonNull Parcel in) {
        mId = in.readString();
        mName = in.readString();
        mDescription = in.readString();
        mExtras = in.readBundle();
    }

    /**
     * Returns true if the route info has all of the required field
     * @hide
     */
    //TODO: Reconsider the validity of a route info when fields are added.
    public boolean isValid() {
        if (TextUtils.isEmpty(getId()) || TextUtils.isEmpty(getName())) {
            return false;
        }
        return true;
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    @Nullable
    public String getDescription() {
        return mDescription;
    }

    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mName);
        dest.writeString(mDescription);
        dest.writeBundle(mExtras);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder()
                .append("MediaRouteInfo{ ")
                .append("id=").append(getId())
                .append(", name=").append(getName())
                .append(" }");
        return result.toString();
    }

    /**
     * Builder for {@link MediaRoute2Info media route info}.
     */
    public static final class Builder {
        String mId;
        String mName;
        String mDescription;
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
        }

        public Builder(@NonNull MediaRoute2Info routeInfo) {
            if (routeInfo == null) {
                throw new IllegalArgumentException("route info must not be null");
            }

            setId(routeInfo.mId);
            setName(routeInfo.mName);
            mDescription = routeInfo.mDescription;
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
         * Builds the {@link MediaRoute2Info media route info}.
         */
        @NonNull
        public MediaRoute2Info build() {
            return new MediaRoute2Info(this);
        }
    }
}
