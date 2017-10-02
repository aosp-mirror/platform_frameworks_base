/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.BitUtils;
import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.StringJoiner;

/**
 * This class represents the capabilities of a network.  This is used both to specify
 * needs to {@link ConnectivityManager} and when inspecting a network.
 *
 * Note that this replaces the old {@link ConnectivityManager#TYPE_MOBILE} method
 * of network selection.  Rather than indicate a need for Wi-Fi because an application
 * needs high bandwidth and risk obsolescence when a new, fast network appears (like LTE),
 * the application should specify it needs high bandwidth.  Similarly if an application
 * needs an unmetered network for a bulk transfer it can specify that rather than assuming
 * all cellular based connections are metered and all Wi-Fi based connections are not.
 */
public final class NetworkCapabilities implements Parcelable {
    private static final String TAG = "NetworkCapabilities";

    /**
     * @hide
     */
    public NetworkCapabilities() {
        clearAll();
        mNetworkCapabilities = DEFAULT_CAPABILITIES;
    }

    public NetworkCapabilities(NetworkCapabilities nc) {
        if (nc != null) {
            mNetworkCapabilities = nc.mNetworkCapabilities;
            mTransportTypes = nc.mTransportTypes;
            mLinkUpBandwidthKbps = nc.mLinkUpBandwidthKbps;
            mLinkDownBandwidthKbps = nc.mLinkDownBandwidthKbps;
            mNetworkSpecifier = nc.mNetworkSpecifier;
            mSignalStrength = nc.mSignalStrength;
        }
    }

    /**
     * Completely clears the contents of this object, removing even the capabilities that are set
     * by default when the object is constructed.
     * @hide
     */
    public void clearAll() {
        mNetworkCapabilities = mTransportTypes = 0;
        mLinkUpBandwidthKbps = mLinkDownBandwidthKbps = 0;
        mNetworkSpecifier = null;
        mSignalStrength = SIGNAL_STRENGTH_UNSPECIFIED;
    }

    /**
     * Represents the network's capabilities.  If any are specified they will be satisfied
     * by any Network that matches all of them.
     */
    private long mNetworkCapabilities;

    /**
     * Indicates this is a network that has the ability to reach the
     * carrier's MMSC for sending and receiving MMS messages.
     */
    public static final int NET_CAPABILITY_MMS            = 0;

    /**
     * Indicates this is a network that has the ability to reach the carrier's
     * SUPL server, used to retrieve GPS information.
     */
    public static final int NET_CAPABILITY_SUPL           = 1;

    /**
     * Indicates this is a network that has the ability to reach the carrier's
     * DUN or tethering gateway.
     */
    public static final int NET_CAPABILITY_DUN            = 2;

    /**
     * Indicates this is a network that has the ability to reach the carrier's
     * FOTA portal, used for over the air updates.
     */
    public static final int NET_CAPABILITY_FOTA           = 3;

    /**
     * Indicates this is a network that has the ability to reach the carrier's
     * IMS servers, used for network registration and signaling.
     */
    public static final int NET_CAPABILITY_IMS            = 4;

    /**
     * Indicates this is a network that has the ability to reach the carrier's
     * CBS servers, used for carrier specific services.
     */
    public static final int NET_CAPABILITY_CBS            = 5;

    /**
     * Indicates this is a network that has the ability to reach a Wi-Fi direct
     * peer.
     */
    public static final int NET_CAPABILITY_WIFI_P2P       = 6;

    /**
     * Indicates this is a network that has the ability to reach a carrier's
     * Initial Attach servers.
     */
    public static final int NET_CAPABILITY_IA             = 7;

    /**
     * Indicates this is a network that has the ability to reach a carrier's
     * RCS servers, used for Rich Communication Services.
     */
    public static final int NET_CAPABILITY_RCS            = 8;

    /**
     * Indicates this is a network that has the ability to reach a carrier's
     * XCAP servers, used for configuration and control.
     */
    public static final int NET_CAPABILITY_XCAP           = 9;

    /**
     * Indicates this is a network that has the ability to reach a carrier's
     * Emergency IMS servers or other services, used for network signaling
     * during emergency calls.
     */
    public static final int NET_CAPABILITY_EIMS           = 10;

    /**
     * Indicates that this network is unmetered.
     */
    public static final int NET_CAPABILITY_NOT_METERED    = 11;

    /**
     * Indicates that this network should be able to reach the internet.
     */
    public static final int NET_CAPABILITY_INTERNET       = 12;

    /**
     * Indicates that this network is available for general use.  If this is not set
     * applications should not attempt to communicate on this network.  Note that this
     * is simply informative and not enforcement - enforcement is handled via other means.
     * Set by default.
     */
    public static final int NET_CAPABILITY_NOT_RESTRICTED = 13;

    /**
     * Indicates that the user has indicated implicit trust of this network.  This
     * generally means it's a sim-selected carrier, a plugged in ethernet, a paired
     * BT device or a wifi the user asked to connect to.  Untrusted networks
     * are probably limited to unknown wifi AP.  Set by default.
     */
    public static final int NET_CAPABILITY_TRUSTED        = 14;

    /**
     * Indicates that this network is not a VPN.  This capability is set by default and should be
     * explicitly cleared for VPN networks.
     */
    public static final int NET_CAPABILITY_NOT_VPN        = 15;

    /**
     * Indicates that connectivity on this network was successfully validated. For example, for a
     * network with NET_CAPABILITY_INTERNET, it means that Internet connectivity was successfully
     * detected.
     */
    public static final int NET_CAPABILITY_VALIDATED      = 16;

    /**
     * Indicates that this network was found to have a captive portal in place last time it was
     * probed.
     */
    public static final int NET_CAPABILITY_CAPTIVE_PORTAL = 17;

    /**
     * Indicates that this network is available for use by apps, and not a network that is being
     * kept up in the background to facilitate fast network switching.
     * @hide
     */
    public static final int NET_CAPABILITY_FOREGROUND = 18;

    private static final int MIN_NET_CAPABILITY = NET_CAPABILITY_MMS;
    private static final int MAX_NET_CAPABILITY = NET_CAPABILITY_FOREGROUND;

    /**
     * Network capabilities that are expected to be mutable, i.e., can change while a particular
     * network is connected.
     */
    private static final long MUTABLE_CAPABILITIES =
            // TRUSTED can change when user explicitly connects to an untrusted network in Settings.
            // http://b/18206275
            (1 << NET_CAPABILITY_TRUSTED) |
            (1 << NET_CAPABILITY_VALIDATED) |
            (1 << NET_CAPABILITY_CAPTIVE_PORTAL) |
            (1 << NET_CAPABILITY_FOREGROUND);

    /**
     * Network capabilities that are not allowed in NetworkRequests. This exists because the
     * NetworkFactory / NetworkAgent model does not deal well with the situation where a
     * capability's presence cannot be known in advance. If such a capability is requested, then we
     * can get into a cycle where the NetworkFactory endlessly churns out NetworkAgents that then
     * get immediately torn down because they do not have the requested capability.
     */
    private static final long NON_REQUESTABLE_CAPABILITIES =
            MUTABLE_CAPABILITIES & ~(1 << NET_CAPABILITY_TRUSTED);

    /**
     * Capabilities that are set by default when the object is constructed.
     */
    private static final long DEFAULT_CAPABILITIES =
            (1 << NET_CAPABILITY_NOT_RESTRICTED) |
            (1 << NET_CAPABILITY_TRUSTED) |
            (1 << NET_CAPABILITY_NOT_VPN);

    /**
     * Capabilities that suggest that a network is restricted.
     * {@see #maybeMarkCapabilitiesRestricted}.
     */
    @VisibleForTesting
    /* package */ static final long RESTRICTED_CAPABILITIES =
            (1 << NET_CAPABILITY_CBS) |
            (1 << NET_CAPABILITY_DUN) |
            (1 << NET_CAPABILITY_EIMS) |
            (1 << NET_CAPABILITY_FOTA) |
            (1 << NET_CAPABILITY_IA) |
            (1 << NET_CAPABILITY_IMS) |
            (1 << NET_CAPABILITY_RCS) |
            (1 << NET_CAPABILITY_XCAP);

    /**
     * Capabilities that suggest that a network is unrestricted.
     * {@see #maybeMarkCapabilitiesRestricted}.
     */
    @VisibleForTesting
    /* package */ static final long UNRESTRICTED_CAPABILITIES =
            (1 << NET_CAPABILITY_INTERNET) |
            (1 << NET_CAPABILITY_MMS) |
            (1 << NET_CAPABILITY_SUPL) |
            (1 << NET_CAPABILITY_WIFI_P2P);

    /**
     * Adds the given capability to this {@code NetworkCapability} instance.
     * Multiple capabilities may be applied sequentially.  Note that when searching
     * for a network to satisfy a request, all capabilities requested must be satisfied.
     *
     * @param capability the {@code NetworkCapabilities.NET_CAPABILITY_*} to be added.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities addCapability(int capability) {
        if (capability < MIN_NET_CAPABILITY || capability > MAX_NET_CAPABILITY) {
            throw new IllegalArgumentException("NetworkCapability out of range");
        }
        mNetworkCapabilities |= 1 << capability;
        return this;
    }

    /**
     * Removes (if found) the given capability from this {@code NetworkCapability} instance.
     *
     * @param capability the {@code NetworkCapabilities.NET_CAPABILTIY_*} to be removed.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities removeCapability(int capability) {
        if (capability < MIN_NET_CAPABILITY || capability > MAX_NET_CAPABILITY) {
            throw new IllegalArgumentException("NetworkCapability out of range");
        }
        mNetworkCapabilities &= ~(1 << capability);
        return this;
    }

    /**
     * Gets all the capabilities set on this {@code NetworkCapability} instance.
     *
     * @return an array of {@code NetworkCapabilities.NET_CAPABILITY_*} values
     *         for this instance.
     * @hide
     */
    public int[] getCapabilities() {
        return BitUtils.unpackBits(mNetworkCapabilities);
    }

    /**
     * Tests for the presence of a capabilitity on this instance.
     *
     * @param capability the {@code NetworkCapabilities.NET_CAPABILITY_*} to be tested for.
     * @return {@code true} if set on this instance.
     */
    public boolean hasCapability(int capability) {
        if (capability < MIN_NET_CAPABILITY || capability > MAX_NET_CAPABILITY) {
            return false;
        }
        return ((mNetworkCapabilities & (1 << capability)) != 0);
    }

    private void combineNetCapabilities(NetworkCapabilities nc) {
        this.mNetworkCapabilities |= nc.mNetworkCapabilities;
    }

    /**
     * Convenience function that returns a human-readable description of the first mutable
     * capability we find. Used to present an error message to apps that request mutable
     * capabilities.
     *
     * @hide
     */
    public String describeFirstNonRequestableCapability() {
        if (hasCapability(NET_CAPABILITY_VALIDATED)) return "NET_CAPABILITY_VALIDATED";
        if (hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)) return "NET_CAPABILITY_CAPTIVE_PORTAL";
        if (hasCapability(NET_CAPABILITY_FOREGROUND)) return "NET_CAPABILITY_FOREGROUND";
        // This cannot happen unless the preceding checks are incomplete.
        if ((mNetworkCapabilities & NON_REQUESTABLE_CAPABILITIES) != 0) {
            return "unknown non-requestable capabilities " + Long.toHexString(mNetworkCapabilities);
        }
        if (mLinkUpBandwidthKbps != 0 || mLinkDownBandwidthKbps != 0) return "link bandwidth";
        if (hasSignalStrength()) return "signalStrength";
        return null;
    }

    private boolean satisfiedByNetCapabilities(NetworkCapabilities nc, boolean onlyImmutable) {
        long networkCapabilities = this.mNetworkCapabilities;
        if (onlyImmutable) {
            networkCapabilities = networkCapabilities & ~MUTABLE_CAPABILITIES;
        }
        return ((nc.mNetworkCapabilities & networkCapabilities) == networkCapabilities);
    }

    /** @hide */
    public boolean equalsNetCapabilities(NetworkCapabilities nc) {
        return (nc.mNetworkCapabilities == this.mNetworkCapabilities);
    }

    private boolean equalsNetCapabilitiesRequestable(NetworkCapabilities that) {
        return ((this.mNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES) ==
                (that.mNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES));
    }

    /**
     * Removes the NET_CAPABILITY_NOT_RESTRICTED capability if all the capabilities it provides are
     * typically provided by restricted networks.
     *
     * TODO: consider:
     * - Renaming it to guessRestrictedCapability and make it set the
     *   restricted capability bit in addition to clearing it.
     * @hide
     */
    public void maybeMarkCapabilitiesRestricted() {
        // Verify there aren't any unrestricted capabilities.  If there are we say
        // the whole thing is unrestricted.
        final boolean hasUnrestrictedCapabilities =
                ((mNetworkCapabilities & UNRESTRICTED_CAPABILITIES) != 0);

        // Must have at least some restricted capabilities.
        final boolean hasRestrictedCapabilities =
                ((mNetworkCapabilities & RESTRICTED_CAPABILITIES) != 0);

        if (hasRestrictedCapabilities && !hasUnrestrictedCapabilities) {
            removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
        }
    }

    /**
     * Representing the transport type.  Apps should generally not care about transport.  A
     * request for a fast internet connection could be satisfied by a number of different
     * transports.  If any are specified here it will be satisfied a Network that matches
     * any of them.  If a caller doesn't care about the transport it should not specify any.
     */
    private long mTransportTypes;

    /**
     * Indicates this network uses a Cellular transport.
     */
    public static final int TRANSPORT_CELLULAR = 0;

    /**
     * Indicates this network uses a Wi-Fi transport.
     */
    public static final int TRANSPORT_WIFI = 1;

    /**
     * Indicates this network uses a Bluetooth transport.
     */
    public static final int TRANSPORT_BLUETOOTH = 2;

    /**
     * Indicates this network uses an Ethernet transport.
     */
    public static final int TRANSPORT_ETHERNET = 3;

    /**
     * Indicates this network uses a VPN transport.
     */
    public static final int TRANSPORT_VPN = 4;

    /**
     * Indicates this network uses a Wi-Fi Aware transport.
     */
    public static final int TRANSPORT_WIFI_AWARE = 5;

    /**
     * Indicates this network uses a LoWPAN transport.
     */
    public static final int TRANSPORT_LOWPAN = 6;

    /** @hide */
    public static final int MIN_TRANSPORT = TRANSPORT_CELLULAR;
    /** @hide */
    public static final int MAX_TRANSPORT = TRANSPORT_LOWPAN;

    /** @hide */
    public static boolean isValidTransport(int transportType) {
        return (MIN_TRANSPORT <= transportType) && (transportType <= MAX_TRANSPORT);
    }

    private static final String[] TRANSPORT_NAMES = {
        "CELLULAR",
        "WIFI",
        "BLUETOOTH",
        "ETHERNET",
        "VPN",
        "WIFI_AWARE",
        "LOWPAN"
    };

    /**
     * Adds the given transport type to this {@code NetworkCapability} instance.
     * Multiple transports may be applied sequentially.  Note that when searching
     * for a network to satisfy a request, any listed in the request will satisfy the request.
     * For example {@code TRANSPORT_WIFI} and {@code TRANSPORT_ETHERNET} added to a
     * {@code NetworkCapabilities} would cause either a Wi-Fi network or an Ethernet network
     * to be selected.  This is logically different than
     * {@code NetworkCapabilities.NET_CAPABILITY_*} listed above.
     *
     * @param transportType the {@code NetworkCapabilities.TRANSPORT_*} to be added.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities addTransportType(int transportType) {
        checkValidTransportType(transportType);
        mTransportTypes |= 1 << transportType;
        setNetworkSpecifier(mNetworkSpecifier); // used for exception checking
        return this;
    }

    /**
     * Removes (if found) the given transport from this {@code NetworkCapability} instance.
     *
     * @param transportType the {@code NetworkCapabilities.TRANSPORT_*} to be removed.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities removeTransportType(int transportType) {
        checkValidTransportType(transportType);
        mTransportTypes &= ~(1 << transportType);
        setNetworkSpecifier(mNetworkSpecifier); // used for exception checking
        return this;
    }

    /**
     * Gets all the transports set on this {@code NetworkCapability} instance.
     *
     * @return an array of {@code NetworkCapabilities.TRANSPORT_*} values
     *         for this instance.
     * @hide
     */
    public int[] getTransportTypes() {
        return BitUtils.unpackBits(mTransportTypes);
    }

    /**
     * Tests for the presence of a transport on this instance.
     *
     * @param transportType the {@code NetworkCapabilities.TRANSPORT_*} to be tested for.
     * @return {@code true} if set on this instance.
     */
    public boolean hasTransport(int transportType) {
        return isValidTransport(transportType) && ((mTransportTypes & (1 << transportType)) != 0);
    }

    private void combineTransportTypes(NetworkCapabilities nc) {
        this.mTransportTypes |= nc.mTransportTypes;
    }

    private boolean satisfiedByTransportTypes(NetworkCapabilities nc) {
        return ((this.mTransportTypes == 0) ||
                ((this.mTransportTypes & nc.mTransportTypes) != 0));
    }

    /** @hide */
    public boolean equalsTransportTypes(NetworkCapabilities nc) {
        return (nc.mTransportTypes == this.mTransportTypes);
    }

    /**
     * Passive link bandwidth.  This is a rough guide of the expected peak bandwidth
     * for the first hop on the given transport.  It is not measured, but may take into account
     * link parameters (Radio technology, allocated channels, etc).
     */
    private int mLinkUpBandwidthKbps;
    private int mLinkDownBandwidthKbps;

    /**
     * Sets the upstream bandwidth for this network in Kbps.  This always only refers to
     * the estimated first hop transport bandwidth.
     * <p>
     * Note that when used to request a network, this specifies the minimum acceptable.
     * When received as the state of an existing network this specifies the typical
     * first hop bandwidth expected.  This is never measured, but rather is inferred
     * from technology type and other link parameters.  It could be used to differentiate
     * between very slow 1xRTT cellular links and other faster networks or even between
     * 802.11b vs 802.11AC wifi technologies.  It should not be used to differentiate between
     * fast backhauls and slow backhauls.
     *
     * @param upKbps the estimated first hop upstream (device to network) bandwidth.
     * @hide
     */
    public void setLinkUpstreamBandwidthKbps(int upKbps) {
        mLinkUpBandwidthKbps = upKbps;
    }

    /**
     * Retrieves the upstream bandwidth for this network in Kbps.  This always only refers to
     * the estimated first hop transport bandwidth.
     *
     * @return The estimated first hop upstream (device to network) bandwidth.
     */
    public int getLinkUpstreamBandwidthKbps() {
        return mLinkUpBandwidthKbps;
    }

    /**
     * Sets the downstream bandwidth for this network in Kbps.  This always only refers to
     * the estimated first hop transport bandwidth.
     * <p>
     * Note that when used to request a network, this specifies the minimum acceptable.
     * When received as the state of an existing network this specifies the typical
     * first hop bandwidth expected.  This is never measured, but rather is inferred
     * from technology type and other link parameters.  It could be used to differentiate
     * between very slow 1xRTT cellular links and other faster networks or even between
     * 802.11b vs 802.11AC wifi technologies.  It should not be used to differentiate between
     * fast backhauls and slow backhauls.
     *
     * @param downKbps the estimated first hop downstream (network to device) bandwidth.
     * @hide
     */
    public void setLinkDownstreamBandwidthKbps(int downKbps) {
        mLinkDownBandwidthKbps = downKbps;
    }

    /**
     * Retrieves the downstream bandwidth for this network in Kbps.  This always only refers to
     * the estimated first hop transport bandwidth.
     *
     * @return The estimated first hop downstream (network to device) bandwidth.
     */
    public int getLinkDownstreamBandwidthKbps() {
        return mLinkDownBandwidthKbps;
    }

    private void combineLinkBandwidths(NetworkCapabilities nc) {
        this.mLinkUpBandwidthKbps =
                Math.max(this.mLinkUpBandwidthKbps, nc.mLinkUpBandwidthKbps);
        this.mLinkDownBandwidthKbps =
                Math.max(this.mLinkDownBandwidthKbps, nc.mLinkDownBandwidthKbps);
    }
    private boolean satisfiedByLinkBandwidths(NetworkCapabilities nc) {
        return !(this.mLinkUpBandwidthKbps > nc.mLinkUpBandwidthKbps ||
                this.mLinkDownBandwidthKbps > nc.mLinkDownBandwidthKbps);
    }
    private boolean equalsLinkBandwidths(NetworkCapabilities nc) {
        return (this.mLinkUpBandwidthKbps == nc.mLinkUpBandwidthKbps &&
                this.mLinkDownBandwidthKbps == nc.mLinkDownBandwidthKbps);
    }

    private NetworkSpecifier mNetworkSpecifier = null;

    /**
     * Sets the optional bearer specific network specifier.
     * This has no meaning if a single transport is also not specified, so calling
     * this without a single transport set will generate an exception, as will
     * subsequently adding or removing transports after this is set.
     * </p>
     *
     * @param networkSpecifier A concrete, parcelable framework class that extends
     *                         NetworkSpecifier.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities setNetworkSpecifier(NetworkSpecifier networkSpecifier) {
        if (networkSpecifier != null && Long.bitCount(mTransportTypes) != 1) {
            throw new IllegalStateException("Must have a single transport specified to use " +
                    "setNetworkSpecifier");
        }

        mNetworkSpecifier = networkSpecifier;

        return this;
    }

    /**
     * Gets the optional bearer specific network specifier.
     *
     * @return The optional {@link NetworkSpecifier} specifying the bearer specific network
     *         specifier. See {@link #setNetworkSpecifier}.
     * @hide
     */
    public NetworkSpecifier getNetworkSpecifier() {
        return mNetworkSpecifier;
    }

    private void combineSpecifiers(NetworkCapabilities nc) {
        if (mNetworkSpecifier != null && !mNetworkSpecifier.equals(nc.mNetworkSpecifier)) {
            throw new IllegalStateException("Can't combine two networkSpecifiers");
        }
        setNetworkSpecifier(nc.mNetworkSpecifier);
    }

    private boolean satisfiedBySpecifier(NetworkCapabilities nc) {
        return mNetworkSpecifier == null || mNetworkSpecifier.satisfiedBy(nc.mNetworkSpecifier)
                || nc.mNetworkSpecifier instanceof MatchAllNetworkSpecifier;
    }

    private boolean equalsSpecifier(NetworkCapabilities nc) {
        return Objects.equals(mNetworkSpecifier, nc.mNetworkSpecifier);
    }

    /**
     * Magic value that indicates no signal strength provided. A request specifying this value is
     * always satisfied.
     *
     * @hide
     */
    public static final int SIGNAL_STRENGTH_UNSPECIFIED = Integer.MIN_VALUE;

    /**
     * Signal strength. This is a signed integer, and higher values indicate better signal.
     * The exact units are bearer-dependent. For example, Wi-Fi uses RSSI.
     */
    private int mSignalStrength;

    /**
     * Sets the signal strength. This is a signed integer, with higher values indicating a stronger
     * signal. The exact units are bearer-dependent. For example, Wi-Fi uses the same RSSI units
     * reported by WifiManager.
     * <p>
     * Note that when used to register a network callback, this specifies the minimum acceptable
     * signal strength. When received as the state of an existing network it specifies the current
     * value. A value of code SIGNAL_STRENGTH_UNSPECIFIED} means no value when received and has no
     * effect when requesting a callback.
     *
     * @param signalStrength the bearer-specific signal strength.
     * @hide
     */
    public void setSignalStrength(int signalStrength) {
        mSignalStrength = signalStrength;
    }

    /**
     * Returns {@code true} if this object specifies a signal strength.
     *
     * @hide
     */
    public boolean hasSignalStrength() {
        return mSignalStrength > SIGNAL_STRENGTH_UNSPECIFIED;
    }

    /**
     * Retrieves the signal strength.
     *
     * @return The bearer-specific signal strength.
     * @hide
     */
    public int getSignalStrength() {
        return mSignalStrength;
    }

    private void combineSignalStrength(NetworkCapabilities nc) {
        this.mSignalStrength = Math.max(this.mSignalStrength, nc.mSignalStrength);
    }

    private boolean satisfiedBySignalStrength(NetworkCapabilities nc) {
        return this.mSignalStrength <= nc.mSignalStrength;
    }

    private boolean equalsSignalStrength(NetworkCapabilities nc) {
        return this.mSignalStrength == nc.mSignalStrength;
    }

    /**
     * Combine a set of Capabilities to this one.  Useful for coming up with the complete set
     * @hide
     */
    public void combineCapabilities(NetworkCapabilities nc) {
        combineNetCapabilities(nc);
        combineTransportTypes(nc);
        combineLinkBandwidths(nc);
        combineSpecifiers(nc);
        combineSignalStrength(nc);
    }

    /**
     * Check if our requirements are satisfied by the given {@code NetworkCapabilities}.
     *
     * @param nc the {@code NetworkCapabilities} that may or may not satisfy our requirements.
     * @param onlyImmutable if {@code true}, do not consider mutable requirements such as link
     *         bandwidth, signal strength, or validation / captive portal status.
     *
     * @hide
     */
    private boolean satisfiedByNetworkCapabilities(NetworkCapabilities nc, boolean onlyImmutable) {
        return (nc != null &&
                satisfiedByNetCapabilities(nc, onlyImmutable) &&
                satisfiedByTransportTypes(nc) &&
                (onlyImmutable || satisfiedByLinkBandwidths(nc)) &&
                satisfiedBySpecifier(nc) &&
                (onlyImmutable || satisfiedBySignalStrength(nc)));
    }

    /**
     * Check if our requirements are satisfied by the given {@code NetworkCapabilities}.
     *
     * @param nc the {@code NetworkCapabilities} that may or may not satisfy our requirements.
     *
     * @hide
     */
    public boolean satisfiedByNetworkCapabilities(NetworkCapabilities nc) {
        return satisfiedByNetworkCapabilities(nc, false);
    }

    /**
     * Check if our immutable requirements are satisfied by the given {@code NetworkCapabilities}.
     *
     * @param nc the {@code NetworkCapabilities} that may or may not satisfy our requirements.
     *
     * @hide
     */
    public boolean satisfiedByImmutableNetworkCapabilities(NetworkCapabilities nc) {
        return satisfiedByNetworkCapabilities(nc, true);
    }

    /**
     * Checks that our immutable capabilities are the same as those of the given
     * {@code NetworkCapabilities} and return a String describing any difference.
     * The returned String is empty if there is no difference.
     *
     * @hide
     */
    public String describeImmutableDifferences(NetworkCapabilities that) {
        if (that == null) {
            return "other NetworkCapabilities was null";
        }

        StringJoiner joiner = new StringJoiner(", ");

        // Ignore NOT_METERED being added or removed as it is effectively dynamic. http://b/63326103
        // TODO: properly support NOT_METERED as a mutable and requestable capability.
        // Ignore DUN being added or removed. http://b/65257223.
        final long mask = ~MUTABLE_CAPABILITIES
                & ~(1 << NET_CAPABILITY_NOT_METERED) & ~(1 << NET_CAPABILITY_DUN);
        long oldImmutableCapabilities = this.mNetworkCapabilities & mask;
        long newImmutableCapabilities = that.mNetworkCapabilities & mask;
        if (oldImmutableCapabilities != newImmutableCapabilities) {
            String before = capabilityNamesOf(BitUtils.unpackBits(oldImmutableCapabilities));
            String after = capabilityNamesOf(BitUtils.unpackBits(newImmutableCapabilities));
            joiner.add(String.format("immutable capabilities changed: %s -> %s", before, after));
        }

        if (!equalsSpecifier(that)) {
            NetworkSpecifier before = this.getNetworkSpecifier();
            NetworkSpecifier after = that.getNetworkSpecifier();
            joiner.add(String.format("specifier changed: %s -> %s", before, after));
        }

        if (!equalsTransportTypes(that)) {
            String before = transportNamesOf(this.getTransportTypes());
            String after = transportNamesOf(that.getTransportTypes());
            joiner.add(String.format("transports changed: %s -> %s", before, after));
        }

        return joiner.toString();
    }

    /**
     * Checks that our requestable capabilities are the same as those of the given
     * {@code NetworkCapabilities}.
     *
     * @hide
     */
    public boolean equalRequestableCapabilities(NetworkCapabilities nc) {
        if (nc == null) return false;
        return (equalsNetCapabilitiesRequestable(nc) &&
                equalsTransportTypes(nc) &&
                equalsSpecifier(nc));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || (obj instanceof NetworkCapabilities == false)) return false;
        NetworkCapabilities that = (NetworkCapabilities)obj;
        return (equalsNetCapabilities(that) &&
                equalsTransportTypes(that) &&
                equalsLinkBandwidths(that) &&
                equalsSignalStrength(that) &&
                equalsSpecifier(that));
    }

    @Override
    public int hashCode() {
        return ((int)(mNetworkCapabilities & 0xFFFFFFFF) +
                ((int)(mNetworkCapabilities >> 32) * 3) +
                ((int)(mTransportTypes & 0xFFFFFFFF) * 5) +
                ((int)(mTransportTypes >> 32) * 7) +
                (mLinkUpBandwidthKbps * 11) +
                (mLinkDownBandwidthKbps * 13) +
                Objects.hashCode(mNetworkSpecifier) * 17 +
                (mSignalStrength * 19));
    }

    @Override
    public int describeContents() {
        return 0;
    }
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mNetworkCapabilities);
        dest.writeLong(mTransportTypes);
        dest.writeInt(mLinkUpBandwidthKbps);
        dest.writeInt(mLinkDownBandwidthKbps);
        dest.writeParcelable((Parcelable) mNetworkSpecifier, flags);
        dest.writeInt(mSignalStrength);
    }

    public static final Creator<NetworkCapabilities> CREATOR =
        new Creator<NetworkCapabilities>() {
            @Override
            public NetworkCapabilities createFromParcel(Parcel in) {
                NetworkCapabilities netCap = new NetworkCapabilities();

                netCap.mNetworkCapabilities = in.readLong();
                netCap.mTransportTypes = in.readLong();
                netCap.mLinkUpBandwidthKbps = in.readInt();
                netCap.mLinkDownBandwidthKbps = in.readInt();
                netCap.mNetworkSpecifier = in.readParcelable(null);
                netCap.mSignalStrength = in.readInt();
                return netCap;
            }
            @Override
            public NetworkCapabilities[] newArray(int size) {
                return new NetworkCapabilities[size];
            }
        };

    @Override
    public String toString() {
        // TODO: enumerate bits for transports and capabilities instead of creating arrays.
        // TODO: use a StringBuilder instead of string concatenation.
        int[] types = getTransportTypes();
        String transports = (types.length > 0) ? " Transports: " + transportNamesOf(types) : "";

        types = getCapabilities();
        String capabilities = (types.length > 0 ? " Capabilities: " : "");
        for (int i = 0; i < types.length; ) {
            capabilities += capabilityNameOf(types[i]);
            if (++i < types.length) capabilities += "&";
        }

        String upBand = ((mLinkUpBandwidthKbps > 0) ? " LinkUpBandwidth>=" +
                mLinkUpBandwidthKbps + "Kbps" : "");
        String dnBand = ((mLinkDownBandwidthKbps > 0) ? " LinkDnBandwidth>=" +
                mLinkDownBandwidthKbps + "Kbps" : "");

        String specifier = (mNetworkSpecifier == null ?
                "" : " Specifier: <" + mNetworkSpecifier + ">");

        String signalStrength = (hasSignalStrength() ? " SignalStrength: " + mSignalStrength : "");

        return "[" + transports + capabilities + upBand + dnBand + specifier + signalStrength + "]";
    }

    /**
     * @hide
     */
    public static String capabilityNamesOf(int[] capabilities) {
        StringJoiner joiner = new StringJoiner("|");
        if (capabilities != null) {
            for (int c : capabilities) {
                joiner.add(capabilityNameOf(c));
            }
        }
        return joiner.toString();
    }

    /**
     * @hide
     */
    public static String capabilityNameOf(int capability) {
        switch (capability) {
            case NET_CAPABILITY_MMS:            return "MMS";
            case NET_CAPABILITY_SUPL:           return "SUPL";
            case NET_CAPABILITY_DUN:            return "DUN";
            case NET_CAPABILITY_FOTA:           return "FOTA";
            case NET_CAPABILITY_IMS:            return "IMS";
            case NET_CAPABILITY_CBS:            return "CBS";
            case NET_CAPABILITY_WIFI_P2P:       return "WIFI_P2P";
            case NET_CAPABILITY_IA:             return "IA";
            case NET_CAPABILITY_RCS:            return "RCS";
            case NET_CAPABILITY_XCAP:           return "XCAP";
            case NET_CAPABILITY_EIMS:           return "EIMS";
            case NET_CAPABILITY_NOT_METERED:    return "NOT_METERED";
            case NET_CAPABILITY_INTERNET:       return "INTERNET";
            case NET_CAPABILITY_NOT_RESTRICTED: return "NOT_RESTRICTED";
            case NET_CAPABILITY_TRUSTED:        return "TRUSTED";
            case NET_CAPABILITY_NOT_VPN:        return "NOT_VPN";
            case NET_CAPABILITY_VALIDATED:      return "VALIDATED";
            case NET_CAPABILITY_CAPTIVE_PORTAL: return "CAPTIVE_PORTAL";
            case NET_CAPABILITY_FOREGROUND:     return "FOREGROUND";
            default:                            return Integer.toString(capability);
        }
    }

    /**
     * @hide
     */
    public static String transportNamesOf(int[] types) {
        StringJoiner joiner = new StringJoiner("|");
        if (types != null) {
            for (int t : types) {
                joiner.add(transportNameOf(t));
            }
        }
        return joiner.toString();
    }

    /**
     * @hide
     */
    public static String transportNameOf(int transport) {
        if (!isValidTransport(transport)) {
            return "UNKNOWN";
        }
        return TRANSPORT_NAMES[transport];
    }

    private static void checkValidTransportType(int transport) {
        Preconditions.checkArgument(
                isValidTransport(transport), "Invalid TransportType " + transport);
    }
}
