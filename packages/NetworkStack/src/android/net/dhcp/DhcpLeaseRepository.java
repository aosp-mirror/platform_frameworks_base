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

import static android.net.NetworkUtils.inet4AddressToIntHTH;
import static android.net.NetworkUtils.intToInet4AddressHTH;
import static android.net.NetworkUtils.prefixLengthToV4NetmaskIntHTH;
import static android.net.dhcp.DhcpLease.EXPIRATION_NEVER;
import static android.net.dhcp.DhcpLease.inet4AddrToString;

import static com.android.server.util.NetworkStackConstants.IPV4_ADDR_BITS;

import static java.lang.Math.min;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.IpPrefix;
import android.net.MacAddress;
import android.net.dhcp.DhcpServer.Clock;
import android.net.util.SharedLog;
import android.util.ArrayMap;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

/**
 * A repository managing IPv4 address assignments through DHCPv4.
 *
 * <p>This class is not thread-safe. All public methods should be called on a common thread or
 * use some synchronization mechanism.
 *
 * <p>Methods are optimized for a small number of allocated leases, assuming that most of the time
 * only 2~10 addresses will be allocated, which is the common case. Managing a large number of
 * addresses is supported but will be slower: some operations have complexity in O(num_leases).
 * @hide
 */
class DhcpLeaseRepository {
    public static final byte[] CLIENTID_UNSPEC = null;
    public static final Inet4Address INETADDR_UNSPEC = null;

    @NonNull
    private final SharedLog mLog;
    @NonNull
    private final Clock mClock;

    @NonNull
    private IpPrefix mPrefix;
    @NonNull
    private Set<Inet4Address> mReservedAddrs;
    private int mSubnetAddr;
    private int mSubnetMask;
    private int mNumAddresses;
    private long mLeaseTimeMs;

    /**
     * Next timestamp when committed or declined leases should be checked for expired ones. This
     * will always be lower than or equal to the time for the first lease to expire: it's OK not to
     * update this when removing entries, but it must always be updated when adding/updating.
     */
    private long mNextExpirationCheck = EXPIRATION_NEVER;

    static class DhcpLeaseException extends Exception {
        DhcpLeaseException(String message) {
            super(message);
        }
    }

    static class OutOfAddressesException extends DhcpLeaseException {
        OutOfAddressesException(String message) {
            super(message);
        }
    }

    static class InvalidAddressException extends DhcpLeaseException {
        InvalidAddressException(String message) {
            super(message);
        }
    }

    static class InvalidSubnetException extends DhcpLeaseException {
        InvalidSubnetException(String message) {
            super(message);
        }
    }

    /**
     * Leases by IP address
     */
    private final ArrayMap<Inet4Address, DhcpLease> mCommittedLeases = new ArrayMap<>();

    /**
     * Map address -> expiration timestamp in ms. Addresses are guaranteed to be valid as defined
     * by {@link #isValidAddress(Inet4Address)}, but are not necessarily otherwise available for
     * assignment.
     */
    private final LinkedHashMap<Inet4Address, Long> mDeclinedAddrs = new LinkedHashMap<>();

    DhcpLeaseRepository(@NonNull IpPrefix prefix, @NonNull Set<Inet4Address> reservedAddrs,
            long leaseTimeMs, @NonNull SharedLog log, @NonNull Clock clock) {
        updateParams(prefix, reservedAddrs, leaseTimeMs);
        mLog = log;
        mClock = clock;
    }

    public void updateParams(@NonNull IpPrefix prefix, @NonNull Set<Inet4Address> reservedAddrs,
            long leaseTimeMs) {
        mPrefix = prefix;
        mReservedAddrs = Collections.unmodifiableSet(new HashSet<>(reservedAddrs));
        mSubnetMask = prefixLengthToV4NetmaskIntHTH(prefix.getPrefixLength());
        mSubnetAddr = inet4AddressToIntHTH((Inet4Address) prefix.getAddress()) & mSubnetMask;
        mNumAddresses = 1 << (IPV4_ADDR_BITS - prefix.getPrefixLength());
        mLeaseTimeMs = leaseTimeMs;

        cleanMap(mCommittedLeases);
        cleanMap(mDeclinedAddrs);
    }

    /**
     * From a map keyed by {@link Inet4Address}, remove entries where the key is invalid (as
     * specified by {@link #isValidAddress(Inet4Address)}), or is a reserved address.
     */
    private <T> void cleanMap(Map<Inet4Address, T> map) {
        final Iterator<Entry<Inet4Address, T>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            final Inet4Address addr = it.next().getKey();
            if (!isValidAddress(addr) || mReservedAddrs.contains(addr)) {
                it.remove();
            }
        }
    }

    /**
     * Get a DHCP offer, to reply to a DHCPDISCOVER. Follows RFC2131 #4.3.1.
     *
     * @param clientId Client identifier option if specified, or {@link #CLIENTID_UNSPEC}
     * @param relayAddr Internet address of the relay (giaddr), can be {@link Inet4Address#ANY}
     * @param reqAddr Requested address by the client (option 50), or {@link #INETADDR_UNSPEC}
     * @param hostname Client-provided hostname, or {@link DhcpLease#HOSTNAME_NONE}
     * @throws OutOfAddressesException The server does not have any available address
     * @throws InvalidSubnetException The lease was requested from an unsupported subnet
     */
    @NonNull
    public DhcpLease getOffer(@Nullable byte[] clientId, @NonNull MacAddress hwAddr,
            @NonNull Inet4Address relayAddr, @Nullable Inet4Address reqAddr,
            @Nullable String hostname) throws OutOfAddressesException, InvalidSubnetException {
        final long currentTime = mClock.elapsedRealtime();
        final long expTime = currentTime + mLeaseTimeMs;

        removeExpiredLeases(currentTime);
        checkValidRelayAddr(relayAddr);

        final DhcpLease currentLease = findByClient(clientId, hwAddr);
        final DhcpLease newLease;
        if (currentLease != null) {
            newLease = currentLease.renewedLease(expTime, hostname);
            mLog.log("Offering extended lease " + newLease);
            // Do not update lease time in the map: the offer is not committed yet.
        } else if (reqAddr != null && isValidAddress(reqAddr) && isAvailable(reqAddr)) {
            newLease = new DhcpLease(clientId, hwAddr, reqAddr, expTime, hostname);
            mLog.log("Offering requested lease " + newLease);
        } else {
            newLease = makeNewOffer(clientId, hwAddr, expTime, hostname);
            mLog.log("Offering new generated lease " + newLease);
        }
        return newLease;
    }

    private void checkValidRelayAddr(@Nullable Inet4Address relayAddr)
            throws InvalidSubnetException {
        // As per #4.3.1, addresses are assigned based on the relay address if present. This
        // implementation only assigns addresses if the relayAddr is inside our configured subnet.
        // This also applies when the client requested a specific address for consistency between
        // requests, and with older behavior.
        if (isIpAddrOutsidePrefix(mPrefix, relayAddr)) {
            throw new InvalidSubnetException("Lease requested by relay from outside of subnet");
        }
    }

    private static boolean isIpAddrOutsidePrefix(@NonNull IpPrefix prefix,
            @Nullable Inet4Address addr) {
        return addr != null && !addr.equals(Inet4Address.ANY) && !prefix.contains(addr);
    }

    @Nullable
    private DhcpLease findByClient(@Nullable byte[] clientId, @NonNull MacAddress hwAddr) {
        for (DhcpLease lease : mCommittedLeases.values()) {
            if (lease.matchesClient(clientId, hwAddr)) {
                return lease;
            }
        }

        // Note this differs from dnsmasq behavior, which would match by hwAddr if clientId was
        // given but no lease keyed on clientId matched. This would prevent one interface from
        // obtaining multiple leases with different clientId.
        return null;
    }

    /**
     * Make a lease conformant to a client DHCPREQUEST or renew the client's existing lease,
     * commit it to the repository and return it.
     *
     * <p>This method always succeeds and commits the lease if it does not throw, and has no side
     * effects if it throws.
     *
     * @param clientId Client identifier option if specified, or {@link #CLIENTID_UNSPEC}
     * @param reqAddr Requested address by the client (option 50), or {@link #INETADDR_UNSPEC}
     * @param sidSet Whether the server identifier was set in the request
     * @return The newly created or renewed lease
     * @throws InvalidAddressException The client provided an address that conflicts with its
     *                                 current configuration, or other committed/reserved leases.
     */
    @NonNull
    public DhcpLease requestLease(@Nullable byte[] clientId, @NonNull MacAddress hwAddr,
            @NonNull Inet4Address clientAddr, @NonNull Inet4Address relayAddr,
            @Nullable Inet4Address reqAddr, boolean sidSet, @Nullable String hostname)
            throws InvalidAddressException, InvalidSubnetException {
        final long currentTime = mClock.elapsedRealtime();
        removeExpiredLeases(currentTime);
        checkValidRelayAddr(relayAddr);
        final DhcpLease assignedLease = findByClient(clientId, hwAddr);

        final Inet4Address leaseAddr = reqAddr != null ? reqAddr : clientAddr;
        if (assignedLease != null) {
            if (sidSet && reqAddr != null) {
                // Client in SELECTING state; remove any current lease before creating a new one.
                mCommittedLeases.remove(assignedLease.getNetAddr());
            } else if (!assignedLease.getNetAddr().equals(leaseAddr)) {
                // reqAddr null (RENEWING/REBINDING): client renewing its own lease for clientAddr.
                // reqAddr set with sid not set (INIT-REBOOT): client verifying configuration.
                // In both cases, throw if clientAddr or reqAddr does not match the known lease.
                throw new InvalidAddressException("Incorrect address for client in "
                        + (reqAddr != null ? "INIT-REBOOT" : "RENEWING/REBINDING"));
            }
        }

        // In the init-reboot case, RFC2131 #4.3.2 says that the server must not reply if
        // assignedLease == null, but dnsmasq will let the client use the requested address if
        // available, when configured with --dhcp-authoritative. This is preferable to avoid issues
        // if the server lost the lease DB: the client would not get a reply because the server
        // does not know their lease.
        // Similarly in RENEWING/REBINDING state, create a lease when possible if the
        // client-provided lease is unknown.
        final DhcpLease lease =
                checkClientAndMakeLease(clientId, hwAddr, leaseAddr, hostname, currentTime);
        mLog.logf("DHCPREQUEST assignedLease %s, reqAddr=%s, sidSet=%s: created/renewed lease %s",
                assignedLease, inet4AddrToString(reqAddr), sidSet, lease);
        return lease;
    }

    /**
     * Check that the client can request the specified address, make or renew the lease if yes, and
     * commit it.
     *
     * <p>This method always succeeds and returns the lease if it does not throw, and has no
     * side-effect if it throws.
     *
     * @return The newly created or renewed, committed lease
     * @throws InvalidAddressException The client provided an address that conflicts with its
     *                                 current configuration, or other committed/reserved leases.
     */
    private DhcpLease checkClientAndMakeLease(@Nullable byte[] clientId, @NonNull MacAddress hwAddr,
            @NonNull Inet4Address addr, @Nullable String hostname, long currentTime)
            throws InvalidAddressException {
        final long expTime = currentTime + mLeaseTimeMs;
        final DhcpLease currentLease = mCommittedLeases.getOrDefault(addr, null);
        if (currentLease != null && !currentLease.matchesClient(clientId, hwAddr)) {
            throw new InvalidAddressException("Address in use");
        }

        final DhcpLease lease;
        if (currentLease == null) {
            if (isValidAddress(addr) && !mReservedAddrs.contains(addr)) {
                lease = new DhcpLease(clientId, hwAddr, addr, expTime, hostname);
            } else {
                throw new InvalidAddressException("Lease not found and address unavailable");
            }
        } else {
            lease = currentLease.renewedLease(expTime, hostname);
        }
        commitLease(lease);
        return lease;
    }

    private void commitLease(@NonNull DhcpLease lease) {
        mCommittedLeases.put(lease.getNetAddr(), lease);
        maybeUpdateEarliestExpiration(lease.getExpTime());
    }

    /**
     * Delete a committed lease from the repository.
     *
     * @return true if a lease matching parameters was found.
     */
    public boolean releaseLease(@Nullable byte[] clientId, @NonNull MacAddress hwAddr,
            @NonNull Inet4Address addr) {
        final DhcpLease currentLease = mCommittedLeases.getOrDefault(addr, null);
        if (currentLease == null) {
            mLog.w("Could not release unknown lease for " + inet4AddrToString(addr));
            return false;
        }
        if (currentLease.matchesClient(clientId, hwAddr)) {
            mCommittedLeases.remove(addr);
            mLog.log("Released lease " + currentLease);
            return true;
        }
        mLog.w(String.format("Not releasing lease %s: does not match client (cid %s, hwAddr %s)",
                currentLease, DhcpLease.clientIdToString(clientId), hwAddr));
        return false;
    }

    public void markLeaseDeclined(@NonNull Inet4Address addr) {
        if (mDeclinedAddrs.containsKey(addr) || !isValidAddress(addr)) {
            mLog.logf("Not marking %s as declined: already declined or not assignable",
                    inet4AddrToString(addr));
            return;
        }
        final long expTime = mClock.elapsedRealtime() + mLeaseTimeMs;
        mDeclinedAddrs.put(addr, expTime);
        mLog.logf("Marked %s as declined expiring %d", inet4AddrToString(addr), expTime);
        maybeUpdateEarliestExpiration(expTime);
    }

    /**
     * Get the list of currently valid committed leases in the repository.
     */
    @NonNull
    public List<DhcpLease> getCommittedLeases() {
        removeExpiredLeases(mClock.elapsedRealtime());
        return new ArrayList<>(mCommittedLeases.values());
    }

    /**
     * Get the set of addresses that have been marked as declined in the repository.
     */
    @NonNull
    public Set<Inet4Address> getDeclinedAddresses() {
        removeExpiredLeases(mClock.elapsedRealtime());
        return new HashSet<>(mDeclinedAddrs.keySet());
    }

    /**
     * Given the expiration time of a new committed lease or declined address, update
     * {@link #mNextExpirationCheck} so it stays lower than or equal to the time for the first lease
     * to expire.
     */
    private void maybeUpdateEarliestExpiration(long expTime) {
        if (expTime < mNextExpirationCheck) {
            mNextExpirationCheck = expTime;
        }
    }

    /**
     * Remove expired entries from a map keyed by {@link Inet4Address}.
     *
     * @param tag Type of lease in the map, for logging
     * @param getExpTime Functor returning the expiration time for an object in the map.
     *                   Must not return null.
     * @return The lowest expiration time among entries remaining in the map
     */
    private <T> long removeExpired(long currentTime, @NonNull Map<Inet4Address, T> map,
            @NonNull String tag, @NonNull Function<T, Long> getExpTime) {
        final Iterator<Entry<Inet4Address, T>> it = map.entrySet().iterator();
        long firstExpiration = EXPIRATION_NEVER;
        while (it.hasNext()) {
            final Entry<Inet4Address, T> lease = it.next();
            final long expTime = getExpTime.apply(lease.getValue());
            if (expTime <= currentTime) {
                mLog.logf("Removing expired %s lease for %s (expTime=%s, currentTime=%s)",
                        tag, lease.getKey(), expTime, currentTime);
                it.remove();
            } else {
                firstExpiration = min(firstExpiration, expTime);
            }
        }
        return firstExpiration;
    }

    /**
     * Go through committed and declined leases and remove the expired ones.
     */
    private void removeExpiredLeases(long currentTime) {
        if (currentTime < mNextExpirationCheck) {
            return;
        }

        final long commExp = removeExpired(
                currentTime, mCommittedLeases, "committed", DhcpLease::getExpTime);
        final long declExp = removeExpired(
                currentTime, mDeclinedAddrs, "declined", Function.identity());

        mNextExpirationCheck = min(commExp, declExp);
    }

    private boolean isAvailable(@NonNull Inet4Address addr) {
        return !mReservedAddrs.contains(addr) && !mCommittedLeases.containsKey(addr);
    }

    /**
     * Get the 0-based index of an address in the subnet.
     *
     * <p>Given ordering of addresses 5.6.7.8 < 5.6.7.9 < 5.6.8.0, the index on a subnet is defined
     * so that the first address is 0, the second 1, etc. For example on a /16, 192.168.0.0 -> 0,
     * 192.168.0.1 -> 1, 192.168.1.0 -> 256
     *
     */
    private int getAddrIndex(int addr) {
        return addr & ~mSubnetMask;
    }

    private int getAddrByIndex(int index) {
        return mSubnetAddr | index;
    }

    /**
     * Get a valid address starting from the supplied one.
     *
     * <p>This only checks that the address is numerically valid for assignment, not whether it is
     * already in use. The return value is always inside the configured prefix, even if the supplied
     * address is not.
     *
     * <p>If the provided address is valid, it is returned as-is. Otherwise, the next valid
     * address (with the ordering in {@link #getAddrIndex(int)}) is returned.
     */
    private int getValidAddress(int addr) {
        final int lastByteMask = 0xff;
        int addrIndex = getAddrIndex(addr); // 0-based index of the address in the subnet

        // Some OSes do not handle addresses in .255 or .0 correctly: avoid those.
        final int lastByte = getAddrByIndex(addrIndex) & lastByteMask;
        if (lastByte == lastByteMask) {
            // Avoid .255 address, and .0 address that follows
            addrIndex = (addrIndex + 2) % mNumAddresses;
        } else if (lastByte == 0) {
            // Avoid .0 address
            addrIndex = (addrIndex + 1) % mNumAddresses;
        }

        // Do not use first or last address of range
        if (addrIndex == 0 || addrIndex == mNumAddresses - 1) {
            // Always valid and not end of range since prefixLength is at most 30 in serving params
            addrIndex = 1;
        }
        return getAddrByIndex(addrIndex);
    }

    /**
     * Returns whether the address is in the configured subnet and part of the assignable range.
     */
    private boolean isValidAddress(Inet4Address addr) {
        final int intAddr = inet4AddressToIntHTH(addr);
        return getValidAddress(intAddr) == intAddr;
    }

    private int getNextAddress(int addr) {
        final int addrIndex = getAddrIndex(addr);
        final int nextAddress = getAddrByIndex((addrIndex + 1) % mNumAddresses);
        return getValidAddress(nextAddress);
    }

    /**
     * Calculate a first candidate address for a client by hashing the hardware address.
     *
     * <p>This will be a valid address as checked by {@link #getValidAddress(int)}, but may be
     * in use.
     *
     * @return An IPv4 address encoded as 32-bit int
     */
    private int getFirstClientAddress(MacAddress hwAddr) {
        // This follows dnsmasq behavior. Advantages are: clients will often get the same
        // offers for different DISCOVER even if the lease was not yet accepted or has expired,
        // and address generation will generally not need to loop through many allocated addresses
        // until it finds a free one.
        int hash = 0;
        for (byte b : hwAddr.toByteArray()) {
            hash += b + (b << 8) + (b << 16);
        }
        // This implementation will not always result in the same IPs as dnsmasq would give out in
        // Android <= P, because it includes invalid and reserved addresses in mNumAddresses while
        // the configured ranges for dnsmasq did not.
        final int addrIndex = hash % mNumAddresses;
        return getValidAddress(getAddrByIndex(addrIndex));
    }

    /**
     * Create a lease that can be offered to respond to a client DISCOVER.
     *
     * <p>This method always succeeds and returns the lease if it does not throw. If no non-declined
     * address is available, it will try to offer the oldest declined address if valid.
     *
     * @throws OutOfAddressesException The server has no address left to offer
     */
    private DhcpLease makeNewOffer(@Nullable byte[] clientId, @NonNull MacAddress hwAddr,
            long expTime, @Nullable String hostname) throws OutOfAddressesException {
        int intAddr = getFirstClientAddress(hwAddr);
        // Loop until a free address is found, or there are no more addresses.
        // There is slightly less than this many usable addresses, but some extra looping is OK
        for (int i = 0; i < mNumAddresses; i++) {
            final Inet4Address addr = intToInet4AddressHTH(intAddr);
            if (isAvailable(addr) && !mDeclinedAddrs.containsKey(addr)) {
                return new DhcpLease(clientId, hwAddr, addr, expTime, hostname);
            }
            intAddr = getNextAddress(intAddr);
        }

        // Try freeing DECLINEd addresses if out of addresses.
        final Iterator<Inet4Address> it = mDeclinedAddrs.keySet().iterator();
        while (it.hasNext()) {
            final Inet4Address addr = it.next();
            it.remove();
            mLog.logf("Out of addresses in address pool: dropped declined addr %s",
                    inet4AddrToString(addr));
            // isValidAddress() is always verified for entries in mDeclinedAddrs.
            // However declined addresses may have been requested (typically by the machine that was
            // already using the address) after being declined.
            if (isAvailable(addr)) {
                return new DhcpLease(clientId, hwAddr, addr, expTime, hostname);
            }
        }

        throw new OutOfAddressesException("No address available for offer");
    }
}
