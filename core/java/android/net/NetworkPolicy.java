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
    public final int cycleDay;
    public final long warningBytes;
    public final long limitBytes;

    public NetworkPolicy(int cycleDay, long warningBytes, long limitBytes) {
        this.cycleDay = cycleDay;
        this.warningBytes = warningBytes;
        this.limitBytes = limitBytes;
    }

    public NetworkPolicy(Parcel in) {
        cycleDay = in.readInt();
        warningBytes = in.readLong();
        limitBytes = in.readLong();
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel dest, int flags) {
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
        if (another == null || limitBytes < another.limitBytes) {
            return -1;
        } else {
            return 1;
        }
    }

    @Override
    public String toString() {
        return "NetworkPolicy: cycleDay=" + cycleDay + ", warningBytes=" + warningBytes
                + ", limitBytes=" + limitBytes;
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
