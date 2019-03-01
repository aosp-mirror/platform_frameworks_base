/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * MatchAllNetworkSpecifier is a marker class used by NetworkFactory classes to indicate
 * that they accept (match) any network specifier in requests.
 *
 * The class must never be used as part of a network request (those semantics aren't specified).
 *
 * @hide
 */
public final class MatchAllNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    /**
     * Utility method which verifies that the ns argument is not a MatchAllNetworkSpecifier and
     * throws an IllegalArgumentException if it is.
     */
    public static void checkNotMatchAllNetworkSpecifier(NetworkSpecifier ns) {
        if (ns instanceof MatchAllNetworkSpecifier) {
            throw new IllegalArgumentException("A MatchAllNetworkSpecifier is not permitted");
        }
    }

    public boolean satisfiedBy(NetworkSpecifier other) {
        /*
         * The method is called by a NetworkRequest to see if it is satisfied by a proposed
         * network (e.g. as offered by a network factory). Since MatchAllNetweorkSpecifier must
         * not be used in network requests this method should never be called.
         */
        throw new IllegalStateException(
                "MatchAllNetworkSpecifier must not be used in NetworkRequests");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MatchAllNetworkSpecifier;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Nothing to write.
    }

    public static final @android.annotation.NonNull Parcelable.Creator<MatchAllNetworkSpecifier> CREATOR =
            new Parcelable.Creator<MatchAllNetworkSpecifier>() {
        public MatchAllNetworkSpecifier createFromParcel(Parcel in) {
            return new MatchAllNetworkSpecifier();
        }
        public MatchAllNetworkSpecifier[] newArray(int size) {
            return new MatchAllNetworkSpecifier[size];
        }
    };
}
