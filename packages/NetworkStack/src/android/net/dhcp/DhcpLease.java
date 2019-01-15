/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.dhcp;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.os.SystemClock;
import android.text.TextUtils;

import com.android.internal.util.HexDump;

import java.net.Inet4Address;
import java.util.Arrays;
import java.util.Objects;

/**
 * An IPv4 address assignment done through DHCPv4.
 * @hide
 */
public class DhcpLease {
    public static final long EXPIRATION_NEVER = Long.MAX_VALUE;
    public static final String HOSTNAME_NONE = null;

    @Nullable
    private final byte[] mClientId;
    @NonNull
    private final MacAddress mHwAddr;
    @NonNull
    private final Inet4Address mNetAddr;
    /**
     * Expiration time for the lease, to compare with {@link SystemClock#elapsedRealtime()}.
     */
    private final long mExpTime;
    @Nullable
    private final String mHostname;

    public DhcpLease(@Nullable byte[] clientId, @NonNull MacAddress hwAddr,
            @NonNull Inet4Address netAddr, long expTime, @Nullable String hostname) {
        mClientId = (clientId == null ? null : Arrays.copyOf(clientId, clientId.length));
        mHwAddr = hwAddr;
        mNetAddr = netAddr;
        mExpTime = expTime;
        mHostname = hostname;
    }

    /**
     * Get the clientId associated with this lease, if any.
     *
     * <p>If the lease is not associated to a clientId, this returns null.
     */
    @Nullable
    public byte[] getClientId() {
        if (mClientId == null) {
            return null;
        }
        return Arrays.copyOf(mClientId, mClientId.length);
    }

    @NonNull
    public MacAddress getHwAddr() {
        return mHwAddr;
    }

    @Nullable
    public String getHostname() {
        return mHostname;
    }

    @NonNull
    public Inet4Address getNetAddr() {
        return mNetAddr;
    }

    public long getExpTime() {
        return mExpTime;
    }

    /**
     * Push back the expiration time of this lease. If the provided time is sooner than the original
     * expiration time, the lease time will not be updated.
     *
     * <p>The lease hostname is updated with the provided one if set.
     * @return A {@link DhcpLease} with expiration time set to max(expTime, currentExpTime)
     */
    public DhcpLease renewedLease(long expTime, @Nullable String hostname) {
        return new DhcpLease(mClientId, mHwAddr, mNetAddr, Math.max(expTime, mExpTime),
                (hostname == null ? mHostname : hostname));
    }

    /**
     * Determine whether this lease matches a client with the specified parameters.
     * @param clientId clientId of the client if any, or null otherwise.
     * @param hwAddr Hardware address of the client.
     */
    public boolean matchesClient(@Nullable byte[] clientId, @NonNull MacAddress hwAddr) {
        if (mClientId != null) {
            return Arrays.equals(mClientId, clientId);
        } else {
            return clientId == null && mHwAddr.equals(hwAddr);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DhcpLease)) {
            return false;
        }
        final DhcpLease other = (DhcpLease) obj;
        return Arrays.equals(mClientId, other.mClientId)
                && mHwAddr.equals(other.mHwAddr)
                && mNetAddr.equals(other.mNetAddr)
                && mExpTime == other.mExpTime
                && TextUtils.equals(mHostname, other.mHostname);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mClientId, mHwAddr, mNetAddr, mHostname, mExpTime);
    }

    static String clientIdToString(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        return HexDump.toHexString(bytes);
    }

    static String inet4AddrToString(@Nullable Inet4Address addr) {
        return (addr == null) ? "null" : addr.getHostAddress();
    }

    @Override
    public String toString() {
        return String.format("clientId: %s, hwAddr: %s, netAddr: %s, expTime: %d, hostname: %s",
                clientIdToString(mClientId), mHwAddr.toString(), inet4AddrToString(mNetAddr),
                mExpTime, mHostname);
    }
}
