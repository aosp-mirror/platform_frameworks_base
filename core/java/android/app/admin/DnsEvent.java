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

package android.app.admin;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that represents a DNS lookup event.
 * @hide
 */
public final class DnsEvent extends NetworkEvent implements Parcelable {

    /** The hostname that was looked up. */
    private final String hostname;

    /** Contains (possibly a subset of) the IP addresses returned. */
    private final String[] ipAddresses;

    /**
     * The number of IP addresses returned from the DNS lookup event. May be different from the
     * length of ipAddresses if there were too many addresses to log.
     */
    private final int ipAddressesCount;

    /** @hide */
    public DnsEvent(String hostname, String[] ipAddresses, int ipAddressesCount,
            String packageName, long timestamp) {
        super(packageName, timestamp);
        this.hostname = hostname;
        this.ipAddresses = ipAddresses;
        this.ipAddressesCount = ipAddressesCount;
    }

    private DnsEvent(Parcel in) {
        this.hostname = in.readString();
        this.ipAddresses = in.createStringArray();
        this.ipAddressesCount = in.readInt();
        this.packageName = in.readString();
        this.timestamp = in.readLong();
    }

    /** Returns the hostname that was looked up. */
    public String getHostname() {
        return hostname;
    }

    /** Returns (possibly a subset of) the IP addresses returned. */
    public String[] getIpAddresses() {
        return ipAddresses;
    }

    /**
     * Returns the number of IP addresses returned from the DNS lookup event. May be different from
     * the length of ipAddresses if there were too many addresses to log.
     */
    public int getIpAddressesCount() {
        return ipAddressesCount;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (ipAddresses != null) {
            for (int i = 0; i < ipAddresses.length; i++) {
                sb.append(ipAddresses[i]);
                if (i < ipAddresses.length - 1) {
                    sb.append(" ");
                }
            }
        } else {
            sb.append("NONE");
        }
        return String.format("DnsEvent(%s, %s, %d, %d, %s)", hostname, sb.toString(),
                ipAddressesCount, timestamp, packageName);
    }

    public static final Parcelable.Creator<DnsEvent> CREATOR
            = new Parcelable.Creator<DnsEvent>() {
        @Override
        public DnsEvent createFromParcel(Parcel in) {
            if (in.readInt() != PARCEL_TOKEN_DNS_EVENT) {
                return null;
            }
            return new DnsEvent(in);
        }

        @Override
        public DnsEvent[] newArray(int size) {
            return new DnsEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        // write parcel token first
        out.writeInt(PARCEL_TOKEN_DNS_EVENT);
        out.writeString(hostname);
        out.writeStringArray(ipAddresses);
        out.writeInt(ipAddressesCount);
        out.writeString(packageName);
        out.writeLong(timestamp);
    }
}

