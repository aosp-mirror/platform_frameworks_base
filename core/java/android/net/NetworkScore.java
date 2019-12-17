/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Object representing the quality of a network as perceived by the user.
 *
 * A NetworkScore object represents the characteristics of a network that affects how good the
 * network is considered for a particular use.
 * @hide
 */
public final class NetworkScore implements Parcelable {

    // The key of bundle which is used to get the legacy network score of NetworkAgentInfo.
    // TODO: Remove this when the transition to NetworkScore is over.
    public static final String LEGACY_SCORE = "LEGACY_SCORE";
    @NonNull
    private final Bundle mExtensions;

    public NetworkScore() {
        mExtensions = new Bundle();
    }

    public NetworkScore(@NonNull NetworkScore source) {
        mExtensions = new Bundle(source.mExtensions);
    }

    /**
     * Put the value of parcelable inside the bundle by key.
     */
    public void putExtension(@Nullable String key, @Nullable Parcelable value) {
        mExtensions.putParcelable(key, value);
    }

    /**
     * Put the value of int inside the bundle by key.
     */
    public void putIntExtension(@Nullable String key, int value) {
        mExtensions.putInt(key, value);
    }

    /**
     * Get the value of non primitive type by key.
     */
    public <T extends Parcelable> T getExtension(@Nullable String key) {
        return mExtensions.getParcelable(key);
    }

    /**
     * Get the value of int by key.
     */
    public int getIntExtension(@Nullable String key) {
        return mExtensions.getInt(key);
    }

    /**
     * Remove the entry by given key.
     */
    public void removeExtension(@Nullable String key) {
        mExtensions.remove(key);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        synchronized (this) {
            dest.writeBundle(mExtensions);
        }
    }

    public static final @NonNull Creator<NetworkScore> CREATOR = new Creator<NetworkScore>() {
        @Override
        public NetworkScore createFromParcel(@NonNull Parcel in) {
            return new NetworkScore(in);
        }

        @Override
        public NetworkScore[] newArray(int size) {
            return new NetworkScore[size];
        }
    };

    private NetworkScore(@NonNull Parcel in) {
        mExtensions = in.readBundle();
    }

    // TODO: Modify this method once new fields are added into this class.
    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof NetworkScore)) {
            return false;
        }
        final NetworkScore other = (NetworkScore) obj;
        return bundlesEqual(mExtensions, other.mExtensions);
    }

    @Override
    public int hashCode() {
        int result = 29;
        for (String key : mExtensions.keySet()) {
            final Object value = mExtensions.get(key);
            // The key may be null, so call Objects.hash() is safer.
            result += 31 * value.hashCode() + 37 * Objects.hash(key);
        }
        return result;
    }

    // mExtensions won't be null since the constructor will create it.
    private boolean bundlesEqual(@NonNull Bundle bundle1, @NonNull Bundle bundle2) {
        if (bundle1 == bundle2) {
            return true;
        }

        // This is unlikely but it's fine to add this clause here.
        if (null == bundle1 || null == bundle2) {
            return false;
        }

        if (bundle1.size() != bundle2.size()) {
            return false;
        }

        for (String key : bundle1.keySet()) {
            final Object value1 = bundle1.get(key);
            final Object value2 = bundle2.get(key);
            if (!Objects.equals(value1, value2)) {
                return false;
            }
        }
        return true;
    }

    /** Convert to a string */
    public String toString() {
        return "NetworkScore[" + mExtensions.toString() + "]";
    }
}
