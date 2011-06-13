/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * Policy for a specific network, including usage cycle and limits to be
 * enforced.
 *
 * @hide
 */
public class NetworkPolicy implements Parcelable, Comparable<NetworkPolicy> {
    public final int networkTemplate;
    public final String subscriberId;
    public int cycleDay;
    public long warningBytes;
    public long limitBytes;

    public static final long WARNING_DISABLED = -1;
    public static final long LIMIT_DISABLED = -1;

    public NetworkPolicy(int networkTemplate, String subscriberId, int cycleDay, long warningBytes,
            long limitBytes) {
        this.networkTemplate = networkTemplate;
        this.subscriberId = subscriberId;
        this.cycleDay = cycleDay;
        this.warningBytes = warningBytes;
        this.limitBytes = limitBytes;
    }

    public NetworkPolicy(Parcel in) {
        networkTemplate = in.readInt();
        subscriberId = in.readString();
        cycleDay = in.readInt();
        warningBytes = in.readLong();
        limitBytes = in.readLong();
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(networkTemplate);
        dest.writeString(subscriberId);
        dest.writeInt(cycleDay);
        dest.writeLong(warningBytes);
        dest.writeLong(limitBytes);
    }

    /** {@inheritDoc} */
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    public int compareTo(NetworkPolicy another) {
        if (another == null || another.limitBytes == LIMIT_DISABLED) {
            // other value is missing or disabled; we win
            return -1;
        }
        if (limitBytes == LIMIT_DISABLED || another.limitBytes < limitBytes) {
            // we're disabled or other limit is smaller; they win
            return 1;
        }
        return 0;
    }

    @Override
    public String toString() {
        return "NetworkPolicy: networkTemplate=" + networkTemplate + ", cycleDay=" + cycleDay
                + ", warningBytes=" + warningBytes + ", limitBytes=" + limitBytes;
    }

    public static final Creator<NetworkPolicy> CREATOR = new Creator<NetworkPolicy>() {
        public NetworkPolicy createFromParcel(Parcel in) {
            return new NetworkPolicy(in);
        }

        public NetworkPolicy[] newArray(int size) {
            return new NetworkPolicy[size];
        }
    };
}
