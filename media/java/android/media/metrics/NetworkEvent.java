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

package android.media.metrics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Playback network event.
 * @hide
 */
public final class NetworkEvent implements Parcelable {
    public static final int NETWORK_TYPE_NONE = 0;
    public static final int NETWORK_TYPE_OTHER = 1;
    public static final int NETWORK_TYPE_WIFI = 2;
    public static final int NETWORK_TYPE_ETHERNET = 3;
    public static final int NETWORK_TYPE_2G = 4;
    public static final int NETWORK_TYPE_3G = 5;
    public static final int NETWORK_TYPE_4G = 6;
    public static final int NETWORK_TYPE_5G_NSA = 7;
    public static final int NETWORK_TYPE_5G_SA = 8;

    private final int mType;
    private final long mTimeSincePlaybackCreatedMillis;

    /** @hide */
    @IntDef(prefix = "NETWORK_TYPE_", value = {
        NETWORK_TYPE_NONE,
        NETWORK_TYPE_OTHER,
        NETWORK_TYPE_WIFI,
        NETWORK_TYPE_ETHERNET,
        NETWORK_TYPE_2G,
        NETWORK_TYPE_3G,
        NETWORK_TYPE_4G,
        NETWORK_TYPE_5G_NSA,
        NETWORK_TYPE_5G_SA
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkType {}

    /**
     * Network type to string.
     */
    public static String networkTypeToString(@NetworkType int value) {
        switch (value) {
            case NETWORK_TYPE_NONE:
                return "NETWORK_TYPE_NONE";
            case NETWORK_TYPE_OTHER:
                return "NETWORK_TYPE_OTHER";
            case NETWORK_TYPE_WIFI:
                return "NETWORK_TYPE_WIFI";
            case NETWORK_TYPE_ETHERNET:
                return "NETWORK_TYPE_ETHERNET";
            case NETWORK_TYPE_2G:
                return "NETWORK_TYPE_2G";
            case NETWORK_TYPE_3G:
                return "NETWORK_TYPE_3G";
            case NETWORK_TYPE_4G:
                return "NETWORK_TYPE_4G";
            case NETWORK_TYPE_5G_NSA:
                return "NETWORK_TYPE_5G_NSA";
            case NETWORK_TYPE_5G_SA:
                return "NETWORK_TYPE_5G_SA";
            default:
                return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new NetworkEvent.
     *
     * @hide
     */
    public NetworkEvent(@NetworkType int type, long timeSincePlaybackCreatedMillis) {
        this.mType = type;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
    }

    @NetworkType
    public int getType() {
        return mType;
    }

    public long getTimeSincePlaybackCreatedMillis() {
        return mTimeSincePlaybackCreatedMillis;
    }

    @Override
    public String toString() {
        return "NetworkEvent { "
                + "type = " + mType + ", "
                + "timeSincePlaybackCreatedMillis = " + mTimeSincePlaybackCreatedMillis
                + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NetworkEvent that = (NetworkEvent) o;
        return mType == that.mType
                && mTimeSincePlaybackCreatedMillis == that.mTimeSincePlaybackCreatedMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mTimeSincePlaybackCreatedMillis);
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeLong(mTimeSincePlaybackCreatedMillis);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ NetworkEvent(@NonNull android.os.Parcel in) {
        int type = in.readInt();
        long timeSincePlaybackCreatedMillis = in.readLong();

        this.mType = type;
        this.mTimeSincePlaybackCreatedMillis = timeSincePlaybackCreatedMillis;
    }

    public static final @NonNull Parcelable.Creator<NetworkEvent> CREATOR =
            new Parcelable.Creator<NetworkEvent>() {
        @Override
        public NetworkEvent[] newArray(int size) {
            return new NetworkEvent[size];
        }

        @Override
        public NetworkEvent createFromParcel(@NonNull Parcel in) {
            return new NetworkEvent(in);
        }
    };

    /**
     * A builder for {@link NetworkEvent}
     */
    public static final class Builder {
        private int mType;
        private long mTimeSincePlaybackCreatedMillis;

        /**
         * Creates a new Builder.
         *
         * @hide
         */
        public Builder() {
        }

        /**
         * Sets network type.
         */
        public @NonNull Builder setType(@NetworkType int value) {
            mType = value;
            return this;
        }

        /**
         * Sets timestamp since the creation in milliseconds.
         */
        public @NonNull Builder setTimeSincePlaybackCreatedMillis(long value) {
            mTimeSincePlaybackCreatedMillis = value;
            return this;
        }

        /** Builds the instance. */
        public @NonNull NetworkEvent build() {
            NetworkEvent o = new NetworkEvent(
                    mType,
                    mTimeSincePlaybackCreatedMillis);
            return o;
        }
    }
}
