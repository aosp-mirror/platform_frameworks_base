/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nan;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Defines the settings (configuration) for a NAN subscribe session. Built using
 * {@link SubscribeSettings.Builder}. Subscribe is done using
 * {@link WifiNanManager#subscribe(SubscribeData, SubscribeSettings, WifiNanSessionListener, int)}
 * or {@link WifiNanSubscribeSession#subscribe(SubscribeData, SubscribeSettings)}.
 *
 * @hide PROPOSED_NAN_API
 */
public class SubscribeSettings implements Parcelable {

    /**
     * Defines a passive subscribe session - i.e. a subscribe session where
     * subscribe packets are not transmitted over-the-air and the device listens
     * and matches to transmitted publish packets. Configuration is done using
     * {@link SubscribeSettings.Builder#setSubscribeType(int)}.
     */
    public static final int SUBSCRIBE_TYPE_PASSIVE = 0;

    /**
     * Defines an active subscribe session - i.e. a subscribe session where
     * subscribe packets are transmitted over-the-air. Configuration is done
     * using {@link SubscribeSettings.Builder#setSubscribeType(int)}.
     */
    public static final int SUBSCRIBE_TYPE_ACTIVE = 1;

    /**
     * @hide
     */
    public final int mSubscribeType;

    /**
     * @hide
     */
    public final int mSubscribeCount;

    /**
     * @hide
     */
    public final int mTtlSec;

    private SubscribeSettings(int subscribeType, int publichCount, int ttlSec) {
        mSubscribeType = subscribeType;
        mSubscribeCount = publichCount;
        mTtlSec = ttlSec;
    }

    @Override
    public String toString() {
        return "SubscribeSettings [mSubscribeType=" + mSubscribeType + ", mSubscribeCount="
                + mSubscribeCount + ", mTtlSec=" + mTtlSec + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSubscribeType);
        dest.writeInt(mSubscribeCount);
        dest.writeInt(mTtlSec);
    }

    public static final Creator<SubscribeSettings> CREATOR = new Creator<SubscribeSettings>() {
        @Override
        public SubscribeSettings[] newArray(int size) {
            return new SubscribeSettings[size];
        }

        @Override
        public SubscribeSettings createFromParcel(Parcel in) {
            int subscribeType = in.readInt();
            int subscribeCount = in.readInt();
            int ttlSec = in.readInt();
            return new SubscribeSettings(subscribeType, subscribeCount, ttlSec);
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof SubscribeSettings)) {
            return false;
        }

        SubscribeSettings lhs = (SubscribeSettings) o;

        return mSubscribeType == lhs.mSubscribeType && mSubscribeCount == lhs.mSubscribeCount
                && mTtlSec == lhs.mTtlSec;
    }

    @Override
    public int hashCode() {
        int result = 17;

        result = 31 * result + mSubscribeType;
        result = 31 * result + mSubscribeCount;
        result = 31 * result + mTtlSec;

        return result;
    }

    /**
     * Builder used to build {@link SubscribeSettings} objects.
     */
    public static final class Builder {
        int mSubscribeType;
        int mSubscribeCount;
        int mTtlSec;

        /**
         * Sets the type of the subscribe session: active (subscribe packets are
         * transmitted over-the-air), or passive (no subscribe packets are
         * transmitted, a match is made against a solicited/active publish
         * session whose packets are transmitted over-the-air).
         *
         * @param subscribeType Subscribe session type: active (
         *            {@link SubscribeSettings#SUBSCRIBE_TYPE_ACTIVE}) or
         *            passive ( {@link SubscribeSettings#SUBSCRIBE_TYPE_PASSIVE}
         *            ).
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setSubscribeType(int subscribeType) {
            if (subscribeType < SUBSCRIBE_TYPE_PASSIVE || subscribeType > SUBSCRIBE_TYPE_ACTIVE) {
                throw new IllegalArgumentException("Invalid subscribeType - " + subscribeType);
            }
            mSubscribeType = subscribeType;
            return this;
        }

        /**
         * Sets the number of times an active (
         * {@link SubscribeSettings.Builder#setSubscribeType(int)}) subscribe
         * session will transmit a packet. When the count is reached an event
         * will be generated for
         * {@link WifiNanSessionListener#onSubscribeTerminated(int)} with reason=
         * {@link WifiNanSessionListener#TERMINATE_REASON_DONE}.
         *
         * @param subscribeCount Number of subscribe packets to transmit.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setSubscribeCount(int subscribeCount) {
            if (subscribeCount < 0) {
                throw new IllegalArgumentException("Invalid subscribeCount - must be non-negative");
            }
            mSubscribeCount = subscribeCount;
            return this;
        }

        /**
         * Sets the time interval (in seconds) an active (
         * {@link SubscribeSettings.Builder#setSubscribeType(int)}) subscribe
         * session will be alive - i.e. transmitting a packet. When the TTL is
         * reached an event will be generated for
         * {@link WifiNanSessionListener#onSubscribeTerminated(int)} with reason=
         * {@link WifiNanSessionListener#TERMINATE_REASON_DONE}.
         *
         * @param ttlSec Lifetime of a subscribe session in seconds.
         * @return The builder to facilitate chaining
         *         {@code builder.setXXX(..).setXXX(..)}.
         */
        public Builder setTtlSec(int ttlSec) {
            if (ttlSec < 0) {
                throw new IllegalArgumentException("Invalid ttlSec - must be non-negative");
            }
            mTtlSec = ttlSec;
            return this;
        }

        /**
         * Build {@link SubscribeSettings} given the current requests made on
         * the builder.
         */
        public SubscribeSettings build() {
            return new SubscribeSettings(mSubscribeType, mSubscribeCount, mTtlSec);
        }
    }
}
