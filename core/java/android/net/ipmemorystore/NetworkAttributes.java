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

package android.net.ipmemorystore;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A POD object to represent attributes of a single L2 network entry.
 * @hide
 */
public class NetworkAttributes {
    private static final boolean DBG = true;

    // The v4 address that was assigned to this device the last time it joined this network.
    // This typically comes from DHCP but could be something else like static configuration.
    // This does not apply to IPv6.
    // TODO : add a list of v6 prefixes for the v6 case.
    @Nullable
    public final Inet4Address assignedV4Address;

    // Optionally supplied by the client if it has an opinion on L3 network. For example, this
    // could be a hash of the SSID + security type on WiFi.
    @Nullable
    public final String groupHint;

    // The list of DNS server addresses.
    @Nullable
    public final List<InetAddress> dnsAddresses;

    // The mtu on this network.
    @Nullable
    public final Integer mtu;

    NetworkAttributes(
            @Nullable final Inet4Address assignedV4Address,
            @Nullable final String groupHint,
            @Nullable final List<InetAddress> dnsAddresses,
            @Nullable final Integer mtu) {
        if (mtu != null && mtu < 0) throw new IllegalArgumentException("MTU can't be negative");
        this.assignedV4Address = assignedV4Address;
        this.groupHint = groupHint;
        this.dnsAddresses = null == dnsAddresses ? null :
                Collections.unmodifiableList(new ArrayList<>(dnsAddresses));
        this.mtu = mtu;
    }

    @VisibleForTesting
    public NetworkAttributes(@NonNull final NetworkAttributesParcelable parcelable) {
        // The call to the other constructor must be the first statement of this constructor,
        // so everything has to be inline
        this((Inet4Address) getByAddressOrNull(parcelable.assignedV4Address),
                parcelable.groupHint,
                blobArrayToInetAddressList(parcelable.dnsAddresses),
                parcelable.mtu >= 0 ? parcelable.mtu : null);
    }

    @Nullable
    private static InetAddress getByAddressOrNull(@Nullable final byte[] address) {
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Nullable
    private static List<InetAddress> blobArrayToInetAddressList(@Nullable final Blob[] blobs) {
        if (null == blobs) return null;
        final ArrayList<InetAddress> list = new ArrayList<>(blobs.length);
        for (final Blob b : blobs) {
            final InetAddress addr = getByAddressOrNull(b.data);
            if (null != addr) list.add(addr);
        }
        return list;
    }

    @Nullable
    private static Blob[] inetAddressListToBlobArray(@Nullable final List<InetAddress> addresses) {
        if (null == addresses) return null;
        final ArrayList<Blob> blobs = new ArrayList<>();
        for (int i = 0; i < addresses.size(); ++i) {
            final InetAddress addr = addresses.get(i);
            if (null == addr) continue;
            final Blob b = new Blob();
            b.data = addr.getAddress();
            blobs.add(b);
        }
        return blobs.toArray(new Blob[0]);
    }

    /** Converts this NetworkAttributes to a parcelable object */
    @NonNull
    public NetworkAttributesParcelable toParcelable() {
        final NetworkAttributesParcelable parcelable = new NetworkAttributesParcelable();
        parcelable.assignedV4Address =
                (null == assignedV4Address) ? null : assignedV4Address.getAddress();
        parcelable.groupHint = groupHint;
        parcelable.dnsAddresses = inetAddressListToBlobArray(dnsAddresses);
        parcelable.mtu = (null == mtu) ? -1 : mtu;
        return parcelable;
    }

    /** @hide */
    public static class Builder {
        @Nullable
        private Inet4Address mAssignedAddress;
        @Nullable
        private String mGroupHint;
        @Nullable
        private List<InetAddress> mDnsAddresses;
        @Nullable
        private Integer mMtu;

        /**
         * Set the assigned address.
         * @param assignedV4Address The assigned address.
         * @return This builder.
         */
        public Builder setAssignedV4Address(@Nullable final Inet4Address assignedV4Address) {
            mAssignedAddress = assignedV4Address;
            return this;
        }

        /**
         * Set the group hint.
         * @param groupHint The group hint.
         * @return This builder.
         */
        public Builder setGroupHint(@Nullable final String groupHint) {
            mGroupHint = groupHint;
            return this;
        }

        /**
         * Set the DNS addresses.
         * @param dnsAddresses The DNS addresses.
         * @return This builder.
         */
        public Builder setDnsAddresses(@Nullable final List<InetAddress> dnsAddresses) {
            if (DBG && null != dnsAddresses) {
                // Parceling code crashes if one of the addresses is null, therefore validate
                // them when running in debug.
                for (final InetAddress address : dnsAddresses) {
                    if (null == address) throw new IllegalArgumentException("Null DNS address");
                }
            }
            this.mDnsAddresses = dnsAddresses;
            return this;
        }

        /**
         * Set the MTU.
         * @param mtu The MTU.
         * @return This builder.
         */
        public Builder setMtu(@Nullable final Integer mtu) {
            if (null != mtu && mtu < 0) throw new IllegalArgumentException("MTU can't be negative");
            mMtu = mtu;
            return this;
        }

        /**
         * Return the built NetworkAttributes object.
         * @return The built NetworkAttributes object.
         */
        public NetworkAttributes build() {
            return new NetworkAttributes(mAssignedAddress, mGroupHint, mDnsAddresses, mMtu);
        }
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (!(o instanceof NetworkAttributes)) return false;
        final NetworkAttributes other = (NetworkAttributes) o;
        return Objects.equals(assignedV4Address, other.assignedV4Address)
                && Objects.equals(groupHint, other.groupHint)
                && Objects.equals(dnsAddresses, other.dnsAddresses)
                && Objects.equals(mtu, other.mtu);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assignedV4Address, groupHint, dnsAddresses, mtu);
    }

    /** Pretty print */
    @Override
    public String toString() {
        final StringJoiner resultJoiner = new StringJoiner(" ", "{", "}");
        final ArrayList<String> nullFields = new ArrayList<>();

        if (null != assignedV4Address) {
            resultJoiner.add("assignedV4Addr :");
            resultJoiner.add(assignedV4Address.toString());
        } else {
            nullFields.add("assignedV4Addr");
        }

        if (null != groupHint) {
            resultJoiner.add("groupHint :");
            resultJoiner.add(groupHint);
        } else {
            nullFields.add("groupHint");
        }

        if (null != dnsAddresses) {
            resultJoiner.add("dnsAddr : [");
            for (final InetAddress addr : dnsAddresses) {
                resultJoiner.add(addr.getHostAddress());
            }
            resultJoiner.add("]");
        } else {
            nullFields.add("dnsAddr");
        }

        if (null != mtu) {
            resultJoiner.add("mtu :");
            resultJoiner.add(mtu.toString());
        } else {
            nullFields.add("mtu");
        }

        if (!nullFields.isEmpty()) {
            resultJoiner.add("; Null fields : [");
            for (final String field : nullFields) {
                resultJoiner.add(field);
            }
            resultJoiner.add("]");
        }

        return resultJoiner.toString();
    }
}
