/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * NetworkSpecifier object for cellular network request. Apps should use the
 * {@link TelephonyNetworkSpecifier.Builder} class to create an instance.
 */
public final class TelephonyNetworkSpecifier extends NetworkSpecifier implements Parcelable {

    private final int mSubId;

    /**
     * Return the subscription Id of current TelephonyNetworkSpecifier object.
     *
     * @return The subscription id.
     */
    public int getSubscriptionId() {
        return mSubId;
    }

    /**
     * @hide
     */
    public TelephonyNetworkSpecifier(int subId) {
        this.mSubId = subId;
    }

    public static final @NonNull Creator<TelephonyNetworkSpecifier> CREATOR =
            new Creator<TelephonyNetworkSpecifier>() {
                @Override
                public TelephonyNetworkSpecifier createFromParcel(Parcel in) {
                    int subId = in.readInt();
                    return new TelephonyNetworkSpecifier(subId);
                }

                @Override
                public TelephonyNetworkSpecifier[] newArray(int size) {
                    return new TelephonyNetworkSpecifier[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSubId);
    }

    @Override
    public int hashCode() {
        return mSubId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof TelephonyNetworkSpecifier)) {
            return false;
        }

        TelephonyNetworkSpecifier lhs = (TelephonyNetworkSpecifier) obj;
        return mSubId == lhs.mSubId;
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("TelephonyNetworkSpecifier [")
                .append("mSubId = ").append(mSubId)
                .append("]")
                .toString();
    }

    /** @hide */
    @Override
    public boolean canBeSatisfiedBy(NetworkSpecifier other) {
        // Although the only caller, NetworkCapabilities, already handled the case of
        // MatchAllNetworkSpecifier, we do it again here in case the API will be used by others.
        // TODO(b/154959809): consider implementing bi-directional specifier instead.
        return equals(other) || other instanceof MatchAllNetworkSpecifier;
    }


    /**
     * Builder to create {@link TelephonyNetworkSpecifier} object.
     */
    public static final class Builder {
        // Integer.MIN_VALUE which is not a valid subId, services as the sentinel to check if
        // subId was set
        private static final int SENTINEL_SUB_ID = Integer.MIN_VALUE;

        private int mSubId;

        public Builder() {
            mSubId = SENTINEL_SUB_ID;
        }

        /**
         * Set the subscription id.
         *
         * @param subId The subscription Id.
         * @return Instance of {@link Builder} to enable the chaining of the builder method.
         */
        public @NonNull Builder setSubscriptionId(int subId) {
            mSubId = subId;
            return this;
        }

        /**
         * Create a NetworkSpecifier for the cellular network request.
         *
         * @return TelephonyNetworkSpecifier object.
         * @throws IllegalArgumentException when subscription Id is not provided through
         *         {@link #setSubscriptionId(int)}.
         */
        public @NonNull TelephonyNetworkSpecifier build() {
            if (mSubId == SENTINEL_SUB_ID) {
                throw new IllegalArgumentException("Subscription Id is not provided.");
            }
            return new TelephonyNetworkSpecifier(mSubId);
        }
    }
}
