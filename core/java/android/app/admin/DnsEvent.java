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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class that represents a DNS lookup event initiated through the standard network stack.
 *
 * <p>It contains information about the originating app as well as the DNS hostname and resolved
 * IP addresses.
 */
public final class DnsEvent extends NetworkEvent implements Parcelable {

    /** The hostname that was looked up. */
    private final String mHostname;

    /** Contains (possibly a subset of) the IP addresses returned. */
    private final String[] mIpAddresses;

    /**
     * The number of IP addresses returned from the DNS lookup event. May be different from the
     * length of ipAddresses if there were too many addresses to log.
     */
    private final int mIpAddressesCount;

    /** @hide */
    public DnsEvent(String hostname, String[] ipAddresses, int ipAddressesCount,
            String packageName, long timestamp) {
        super(packageName, timestamp);
        this.mHostname = hostname;
        this.mIpAddresses = ipAddresses;
        this.mIpAddressesCount = ipAddressesCount;
    }

    private DnsEvent(Parcel in) {
        this.mHostname = in.readString();
        this.mIpAddresses = in.createStringArray();
        this.mIpAddressesCount = in.readInt();
        this.mPackageName = in.readString();
        this.mTimestamp = in.readLong();
        this.mId = in.readLong();
    }

    /** Returns the hostname that was looked up. */
    public String getHostname() {
        return mHostname;
    }

    /** Returns (possibly a subset of) the IP addresses returned. */
    public List<InetAddress> getInetAddresses() {
        if (mIpAddresses == null || mIpAddresses.length == 0) {
            return Collections.emptyList();
        }
        final List<InetAddress> inetAddresses = new ArrayList<>(mIpAddresses.length);
        for (final String ipAddress : mIpAddresses) {
            try {
                // ipAddress is already an address, not a host name, no DNS resolution will happen.
                inetAddresses.add(InetAddress.getByName(ipAddress));
            } catch (UnknownHostException e) {
                // Should never happen as we aren't passing a host name.
            }
        }
        return inetAddresses;
    }

    /**
     * Returns the number of IP addresses returned from the DNS lookup event. May be different from
     * the length of the list returned by {@link #getInetAddresses()} if there were too many
     * addresses to log.
     */
    public int getTotalResolvedAddressCount() {
        return mIpAddressesCount;
    }

    @Override
    public String toString() {
        return String.format("DnsEvent(%d, %s, %s, %d, %d, %s)", mId, mHostname,
                (mIpAddresses == null) ? "NONE" : String.join(" ", mIpAddresses),
                mIpAddressesCount, mTimestamp, mPackageName);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<DnsEvent> CREATOR
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
        out.writeString(mHostname);
        out.writeStringArray(mIpAddresses);
        out.writeInt(mIpAddressesCount);
        out.writeString(mPackageName);
        out.writeLong(mTimestamp);
        out.writeLong(mId);
    }
}
