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

    // Weight cutoff for grouping. To group, a similarity score is computed with the following
    // algorithm : if both fields are non-null and equals() then add their assigned weight, else if
    // both are null then add a portion of their assigned weight (see NULL_MATCH_WEIGHT),
    // otherwise add nothing.
    // As a guideline, this should be something like 60~75% of the total weights in this class. The
    // design states "in essence a reader should imagine that if two important columns don't match,
    // or one important and several unimportant columns don't match then the two records are
    // considered a different group".
    private static final float TOTAL_WEIGHT_CUTOFF = 520.0f;
    // The portion of the weight that is earned when scoring group-sameness by having both columns
    // being null. This is because some networks rightfully don't have some attributes (e.g. a
    // V6-only network won't have an assigned V4 address) and both being null should count for
    // something, but attributes may also be null just because data is unavailable.
    private static final float NULL_MATCH_WEIGHT = 0.25f;

    // The v4 address that was assigned to this device the last time it joined this network.
    // This typically comes from DHCP but could be something else like static configuration.
    // This does not apply to IPv6.
    // TODO : add a list of v6 prefixes for the v6 case.
    @Nullable
    public final Inet4Address assignedV4Address;
    private static final float WEIGHT_ASSIGNEDV4ADDR = 300.0f;

    // The lease expiry timestamp of v4 address allocated from DHCP server, in milliseconds.
    @Nullable
    public final Long assignedV4AddressExpiry;
    // lease expiry doesn't imply any correlation between "the same lease expiry value" and "the
    // same L3 network".
    private static final float WEIGHT_ASSIGNEDV4ADDREXPIRY = 0.0f;

    // Optionally supplied by the client if it has an opinion on L3 network. For example, this
    // could be a hash of the SSID + security type on WiFi.
    @Nullable
    public final String groupHint;
    private static final float WEIGHT_GROUPHINT = 300.0f;

    // The list of DNS server addresses.
    @Nullable
    public final List<InetAddress> dnsAddresses;
    private static final float WEIGHT_DNSADDRESSES = 200.0f;

    // The mtu on this network.
    @Nullable
    public final Integer mtu;
    private static final float WEIGHT_MTU = 50.0f;

    // The sum of all weights in this class. Tests ensure that this stays equal to the total of
    // all weights.
    /** @hide */
    @VisibleForTesting
    public static final float TOTAL_WEIGHT = WEIGHT_ASSIGNEDV4ADDR
            + WEIGHT_ASSIGNEDV4ADDREXPIRY
            + WEIGHT_GROUPHINT
            + WEIGHT_DNSADDRESSES
            + WEIGHT_MTU;

    /** @hide */
    @VisibleForTesting
    public NetworkAttributes(
            @Nullable final Inet4Address assignedV4Address,
            @Nullable final Long assignedV4AddressExpiry,
            @Nullable final String groupHint,
            @Nullable final List<InetAddress> dnsAddresses,
            @Nullable final Integer mtu) {
        if (mtu != null && mtu < 0) throw new IllegalArgumentException("MTU can't be negative");
        if (assignedV4AddressExpiry != null && assignedV4AddressExpiry <= 0) {
            throw new IllegalArgumentException("lease expiry can't be negative or zero");
        }
        this.assignedV4Address = assignedV4Address;
        this.assignedV4AddressExpiry = assignedV4AddressExpiry;
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
                parcelable.assignedV4AddressExpiry > 0
                        ? parcelable.assignedV4AddressExpiry : null,
                parcelable.groupHint,
                blobArrayToInetAddressList(parcelable.dnsAddresses),
                parcelable.mtu >= 0 ? parcelable.mtu : null);
    }

    @Nullable
    private static InetAddress getByAddressOrNull(@Nullable final byte[] address) {
        if (null == address) return null;
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
        parcelable.assignedV4AddressExpiry =
                (null == assignedV4AddressExpiry) ? 0 : assignedV4AddressExpiry;
        parcelable.groupHint = groupHint;
        parcelable.dnsAddresses = inetAddressListToBlobArray(dnsAddresses);
        parcelable.mtu = (null == mtu) ? -1 : mtu;
        return parcelable;
    }

    private float samenessContribution(final float weight,
            @Nullable final Object o1, @Nullable final Object o2) {
        if (null == o1) {
            return (null == o2) ? weight * NULL_MATCH_WEIGHT : 0f;
        }
        return Objects.equals(o1, o2) ? weight : 0f;
    }

    /** @hide */
    public float getNetworkGroupSamenessConfidence(@NonNull final NetworkAttributes o) {
        final float samenessScore =
                samenessContribution(WEIGHT_ASSIGNEDV4ADDR, assignedV4Address, o.assignedV4Address)
                + samenessContribution(WEIGHT_ASSIGNEDV4ADDREXPIRY, assignedV4AddressExpiry,
                      o.assignedV4AddressExpiry)
                + samenessContribution(WEIGHT_GROUPHINT, groupHint, o.groupHint)
                + samenessContribution(WEIGHT_DNSADDRESSES, dnsAddresses, o.dnsAddresses)
                + samenessContribution(WEIGHT_MTU, mtu, o.mtu);
        // The minimum is 0, the max is TOTAL_WEIGHT and should be represented by 1.0, and
        // TOTAL_WEIGHT_CUTOFF should represent 0.5, but there is no requirement that
        // TOTAL_WEIGHT_CUTOFF would be half of TOTAL_WEIGHT (indeed, it should not be).
        // So scale scores under the cutoff between 0 and 0.5, and the scores over the cutoff
        // between 0.5 and 1.0.
        if (samenessScore < TOTAL_WEIGHT_CUTOFF) {
            return samenessScore / (TOTAL_WEIGHT_CUTOFF * 2);
        } else {
            return (samenessScore - TOTAL_WEIGHT_CUTOFF) / (TOTAL_WEIGHT - TOTAL_WEIGHT_CUTOFF) / 2
                    + 0.5f;
        }
    }

    /** @hide */
    public static class Builder {
        @Nullable
        private Inet4Address mAssignedAddress;
        @Nullable
        private Long mAssignedAddressExpiry;
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
         * Set the lease expiry timestamp of assigned v4 address. Long.MAX_VALUE is used
         * to represent "infinite lease".
         *
         * @param assignedV4AddressExpiry The lease expiry timestamp of assigned v4 address.
         * @return This builder.
         */
        public Builder setAssignedV4AddressExpiry(
                @Nullable final Long assignedV4AddressExpiry) {
            if (null != assignedV4AddressExpiry && assignedV4AddressExpiry <= 0) {
                throw new IllegalArgumentException("lease expiry can't be negative or zero");
            }
            mAssignedAddressExpiry = assignedV4AddressExpiry;
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
            return new NetworkAttributes(mAssignedAddress, mAssignedAddressExpiry,
                  mGroupHint, mDnsAddresses, mMtu);
        }
    }

    /** @hide */
    public boolean isEmpty() {
        return (null == assignedV4Address) && (null == assignedV4AddressExpiry)
                && (null == groupHint) && (null == dnsAddresses) && (null == mtu);
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (!(o instanceof NetworkAttributes)) return false;
        final NetworkAttributes other = (NetworkAttributes) o;
        return Objects.equals(assignedV4Address, other.assignedV4Address)
                && Objects.equals(assignedV4AddressExpiry, other.assignedV4AddressExpiry)
                && Objects.equals(groupHint, other.groupHint)
                && Objects.equals(dnsAddresses, other.dnsAddresses)
                && Objects.equals(mtu, other.mtu);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assignedV4Address, assignedV4AddressExpiry,
                groupHint, dnsAddresses, mtu);
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

        if (null != assignedV4AddressExpiry) {
            resultJoiner.add("assignedV4AddressExpiry :");
            resultJoiner.add(assignedV4AddressExpiry.toString());
        } else {
            nullFields.add("assignedV4AddressExpiry");
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
