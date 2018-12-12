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

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.net.ConnectivityManager.NetworkCallback;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.BitUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Representation of the capabilities of an active network. Instances are
 * typically obtained through
 * {@link NetworkCallback#onCapabilitiesChanged(Network, NetworkCapabilities)}
 * or {@link ConnectivityManager#getNetworkCapabilities(Network)}.
 * <p>
 * This replaces the old {@link ConnectivityManager#TYPE_MOBILE} method of
 * network selection. Rather than indicate a need for Wi-Fi because an
 * application needs high bandwidth and risk obsolescence when a new, fast
 * network appears (like LTE), the application should specify it needs high
 * bandwidth. Similarly if an application needs an unmetered network for a bulk
 * transfer it can specify that rather than assuming all cellular based
 * connections are metered and all Wi-Fi based connections are not.
 */
public final class NetworkCapabilities implements Parcelable {
    private static final String TAG = "NetworkCapabilities";
    private static final int INVALID_UID = -1;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public NetworkCapabilities() {
        clearAll();
        mNetworkCapabilities = DEFAULT_CAPABILITIES;
    }

    public NetworkCapabilities(NetworkCapabilities nc) {
        if (nc != null) {
            set(nc);
        }
    }

    /**
     * Completely clears the contents of this object, removing even the capabilities that are set
     * by default when the object is constructed.
     * @hide
     */
    public void clearAll() {
        mNetworkCapabilities = mTransportTypes = mUnwantedNetworkCapabilities = 0;
        mLinkUpBandwidthKbps = mLinkDownBandwidthKbps = LINK_BANDWIDTH_UNSPECIFIED;
        mNetworkSpecifier = null;
        mSignalStrength = SIGNAL_STRENGTH_UNSPECIFIED;
        mUids = null;
        mEstablishingVpnAppUid = INVALID_UID;
        mSSID = null;
    }

    /**
     * Set all contents of this object to the contents of a NetworkCapabilities.
     * @hide
     */
    public void set(NetworkCapabilities nc) {
        mNetworkCapabilities = nc.mNetworkCapabilities;
        mTransportTypes = nc.mTransportTypes;
        mLinkUpBandwidthKbps = nc.mLinkUpBandwidthKbps;
        mLinkDownBandwidthKbps = nc.mLinkDownBandwidthKbps;
        mNetworkSpecifier = nc.mNetworkSpecifier;
        mSignalStrength = nc.mSignalStrength;
        setUids(nc.mUids); // Will make the defensive copy
        mEstablishingVpnAppUid = nc.mEstablishingVpnAppUid;
        mUnwantedNetworkCapabilities = nc.mUnwantedNetworkCapabilities;
        mSSID = nc.mSSID;
    }

    /**
     * Represents the network's capabilities.  If any are specified they will be satisfied
     * by any Network that matches all of them.
     */
    @UnsupportedAppUsage
    private long mNetworkCapabilities;

    /**
     * If any capabilities specified here they must not exist in the matching Network.
     */
    private long mUnwantedNetworkCapabilities;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "NET_CAPABILITY_" }, value = {
            NET_CAPABILITY_MMS,
            NET_CAPABILITY_SUPL,
            NET_CAPABILITY_DUN,
            NET_CAPABILITY_FOTA,
            NET_CAPABILITY_IMS,
            NET_CAPABILITY_CBS,
            NET_CAPABILITY_WIFI_P2P,
            NET_CAPABILITY_IA,
            NET_CAPABILITY_RCS,
            NET_CAPABILITY_XCAP,
            NET_CAPABILITY_EIMS,
            NET_CAPABILITY_NOT_METERED,
            NET_CAPABILITY_INTERNET,
            NET_CAPABILITY_NOT_RESTRICTED,
            NET_CAPABILITY_TRUSTED,
            NET_CAPABILITY_NOT_VPN,
            NET_CAPABILITY_VALIDATED,
            NET_CAPABILITY_CAPTIVE_PORTAL,
            NET_CAPABILITY_NOT_ROAMING,
            NET_CAPABILITY_FOREGROUND,
            NET_CAPABILITY_NOT_CONGESTED,
            NET_CAPABILITY_NOT_SUSPENDED,
            NET_CAPABILITY_OEM_PAID,
    })
    public @interface NetCapability { }

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
     * Indicates that this network is not roaming.
     */
    public static final int NET_CAPABILITY_NOT_ROAMING = 18;

    /**
     * Indicates that this network is available for use by apps, and not a network that is being
     * kept up in the background to facilitate fast network switching.
     */
    public static final int NET_CAPABILITY_FOREGROUND = 19;

    /**
     * Indicates that this network is not congested.
     * <p>
     * When a network is congested, applications should defer network traffic
     * that can be done at a later time, such as uploading analytics.
     */
    public static final int NET_CAPABILITY_NOT_CONGESTED = 20;

    /**
     * Indicates that this network is not currently suspended.
     * <p>
     * When a network is suspended, the network's IP addresses and any connections
     * established on the network remain valid, but the network is temporarily unable
     * to transfer data. This can happen, for example, if a cellular network experiences
     * a temporary loss of signal, such as when driving through a tunnel, etc.
     * A network with this capability is not suspended, so is expected to be able to
     * transfer data.
     */
    public static final int NET_CAPABILITY_NOT_SUSPENDED = 21;

    /**
     * Indicates that traffic that goes through this network is paid by oem. For example,
     * this network can be used by system apps to upload telemetry data.
     * @hide
     */
    @SystemApi
    public static final int NET_CAPABILITY_OEM_PAID = 22;

    private static final int MIN_NET_CAPABILITY = NET_CAPABILITY_MMS;
    private static final int MAX_NET_CAPABILITY = NET_CAPABILITY_OEM_PAID;

    /**
     * Network capabilities that are expected to be mutable, i.e., can change while a particular
     * network is connected.
     */
    private static final long MUTABLE_CAPABILITIES =
            // TRUSTED can change when user explicitly connects to an untrusted network in Settings.
            // http://b/18206275
            (1 << NET_CAPABILITY_TRUSTED)
            | (1 << NET_CAPABILITY_VALIDATED)
            | (1 << NET_CAPABILITY_CAPTIVE_PORTAL)
            | (1 << NET_CAPABILITY_NOT_ROAMING)
            | (1 << NET_CAPABILITY_FOREGROUND)
            | (1 << NET_CAPABILITY_NOT_CONGESTED)
            | (1 << NET_CAPABILITY_NOT_SUSPENDED);

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
     * {@see #maybeMarkCapabilitiesRestricted}, {@see #FORCE_RESTRICTED_CAPABILITIES}
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
     * Capabilities that force network to be restricted.
     * {@see #maybeMarkCapabilitiesRestricted}.
     */
    private static final long FORCE_RESTRICTED_CAPABILITIES =
            (1 << NET_CAPABILITY_OEM_PAID);

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
     * <p>
     * If the given capability was previously added to the list of unwanted capabilities
     * then the capability will also be removed from the list of unwanted capabilities.
     *
     * @param capability the capability to be added.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    @UnsupportedAppUsage
    public NetworkCapabilities addCapability(@NetCapability int capability) {
        checkValidCapability(capability);
        mNetworkCapabilities |= 1 << capability;
        mUnwantedNetworkCapabilities &= ~(1 << capability);  // remove from unwanted capability list
        return this;
    }

    /**
     * Adds the given capability to the list of unwanted capabilities of this
     * {@code NetworkCapability} instance.  Multiple unwanted capabilities may be applied
     * sequentially.  Note that when searching for a network to satisfy a request, the network
     * must not contain any capability from unwanted capability list.
     * <p>
     * If the capability was previously added to the list of required capabilities (for
     * example, it was there by default or added using {@link #addCapability(int)} method), then
     * it will be removed from the list of required capabilities as well.
     *
     * @see #addCapability(int)
     * @hide
     */
    public void addUnwantedCapability(@NetCapability int capability) {
        checkValidCapability(capability);
        mUnwantedNetworkCapabilities |= 1 << capability;
        mNetworkCapabilities &= ~(1 << capability);  // remove from requested capabilities
    }

    /**
     * Removes (if found) the given capability from this {@code NetworkCapability} instance.
     * <p>
     * Note that this method removes capabilities that were added via {@link #addCapability(int)},
     * {@link #addUnwantedCapability(int)} or {@link #setCapabilities(int[], int[])} .
     *
     * @param capability the capability to be removed.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    @UnsupportedAppUsage
    public NetworkCapabilities removeCapability(@NetCapability int capability) {
        checkValidCapability(capability);
        final long mask = ~(1 << capability);
        mNetworkCapabilities &= mask;
        mUnwantedNetworkCapabilities &= mask;
        return this;
    }

    /**
     * Sets (or clears) the given capability on this {@link NetworkCapabilities}
     * instance.
     *
     * @hide
     */
    public NetworkCapabilities setCapability(@NetCapability int capability, boolean value) {
        if (value) {
            addCapability(capability);
        } else {
            removeCapability(capability);
        }
        return this;
    }

    /**
     * Gets all the capabilities set on this {@code NetworkCapability} instance.
     *
     * @return an array of capability values for this instance.
     * @hide
     */
    @TestApi
    public @NetCapability int[] getCapabilities() {
        return BitUtils.unpackBits(mNetworkCapabilities);
    }

    /**
     * Gets all the unwanted capabilities set on this {@code NetworkCapability} instance.
     *
     * @return an array of unwanted capability values for this instance.
     * @hide
     */
    public @NetCapability int[] getUnwantedCapabilities() {
        return BitUtils.unpackBits(mUnwantedNetworkCapabilities);
    }


    /**
     * Sets all the capabilities set on this {@code NetworkCapability} instance.
     * This overwrites any existing capabilities.
     *
     * @hide
     */
    public void setCapabilities(@NetCapability int[] capabilities,
            @NetCapability int[] unwantedCapabilities) {
        mNetworkCapabilities = BitUtils.packBits(capabilities);
        mUnwantedNetworkCapabilities = BitUtils.packBits(unwantedCapabilities);
    }

    /**
     * @deprecated use {@link #setCapabilities(int[], int[])}
     * @hide
     */
    @Deprecated
    public void setCapabilities(@NetCapability int[] capabilities) {
        setCapabilities(capabilities, new int[] {});
    }

    /**
     * Tests for the presence of a capability on this instance.
     *
     * @param capability the capabilities to be tested for.
     * @return {@code true} if set on this instance.
     */
    public boolean hasCapability(@NetCapability int capability) {
        return isValidCapability(capability)
                && ((mNetworkCapabilities & (1 << capability)) != 0);
    }

    /** @hide */
    public boolean hasUnwantedCapability(@NetCapability int capability) {
        return isValidCapability(capability)
                && ((mUnwantedNetworkCapabilities & (1 << capability)) != 0);
    }

    /** Note this method may result in having the same capability in wanted and unwanted lists. */
    private void combineNetCapabilities(NetworkCapabilities nc) {
        this.mNetworkCapabilities |= nc.mNetworkCapabilities;
        this.mUnwantedNetworkCapabilities |= nc.mUnwantedNetworkCapabilities;
    }

    /**
     * Convenience function that returns a human-readable description of the first mutable
     * capability we find. Used to present an error message to apps that request mutable
     * capabilities.
     *
     * @hide
     */
    public String describeFirstNonRequestableCapability() {
        final long nonRequestable = (mNetworkCapabilities | mUnwantedNetworkCapabilities)
                & NON_REQUESTABLE_CAPABILITIES;

        if (nonRequestable != 0) {
            return capabilityNameOf(BitUtils.unpackBits(nonRequestable)[0]);
        }
        if (mLinkUpBandwidthKbps != 0 || mLinkDownBandwidthKbps != 0) return "link bandwidth";
        if (hasSignalStrength()) return "signalStrength";
        return null;
    }

    private boolean satisfiedByNetCapabilities(NetworkCapabilities nc, boolean onlyImmutable) {
        long requestedCapabilities = mNetworkCapabilities;
        long requestedUnwantedCapabilities = mUnwantedNetworkCapabilities;
        long providedCapabilities = nc.mNetworkCapabilities;

        if (onlyImmutable) {
            requestedCapabilities &= ~MUTABLE_CAPABILITIES;
            requestedUnwantedCapabilities &= ~MUTABLE_CAPABILITIES;
        }
        return ((providedCapabilities & requestedCapabilities) == requestedCapabilities)
                && ((requestedUnwantedCapabilities & providedCapabilities) == 0);
    }

    /** @hide */
    public boolean equalsNetCapabilities(NetworkCapabilities nc) {
        return (nc.mNetworkCapabilities == this.mNetworkCapabilities)
                && (nc.mUnwantedNetworkCapabilities == this.mUnwantedNetworkCapabilities);
    }

    private boolean equalsNetCapabilitiesRequestable(NetworkCapabilities that) {
        return ((this.mNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES) ==
                (that.mNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES))
                && ((this.mUnwantedNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES) ==
                (that.mUnwantedNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES));
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
        // Check if we have any capability that forces the network to be restricted.
        final boolean forceRestrictedCapability =
                (mNetworkCapabilities & FORCE_RESTRICTED_CAPABILITIES) != 0;

        // Verify there aren't any unrestricted capabilities.  If there are we say
        // the whole thing is unrestricted unless it is forced to be restricted.
        final boolean hasUnrestrictedCapabilities =
                (mNetworkCapabilities & UNRESTRICTED_CAPABILITIES) != 0;

        // Must have at least some restricted capabilities.
        final boolean hasRestrictedCapabilities =
                (mNetworkCapabilities & RESTRICTED_CAPABILITIES) != 0;

        if (forceRestrictedCapability
                || (hasRestrictedCapabilities && !hasUnrestrictedCapabilities)) {
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

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "TRANSPORT_" }, value = {
            TRANSPORT_CELLULAR,
            TRANSPORT_WIFI,
            TRANSPORT_BLUETOOTH,
            TRANSPORT_ETHERNET,
            TRANSPORT_VPN,
            TRANSPORT_WIFI_AWARE,
            TRANSPORT_LOWPAN,
    })
    public @interface Transport { }

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
    public static boolean isValidTransport(@Transport int transportType) {
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
     * @param transportType the transport type to be added.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    @UnsupportedAppUsage
    public NetworkCapabilities addTransportType(@Transport int transportType) {
        checkValidTransportType(transportType);
        mTransportTypes |= 1 << transportType;
        setNetworkSpecifier(mNetworkSpecifier); // used for exception checking
        return this;
    }

    /**
     * Removes (if found) the given transport from this {@code NetworkCapability} instance.
     *
     * @param transportType the transport type to be removed.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities removeTransportType(@Transport int transportType) {
        checkValidTransportType(transportType);
        mTransportTypes &= ~(1 << transportType);
        setNetworkSpecifier(mNetworkSpecifier); // used for exception checking
        return this;
    }

    /**
     * Sets (or clears) the given transport on this {@link NetworkCapabilities}
     * instance.
     *
     * @hide
     */
    public NetworkCapabilities setTransportType(@Transport int transportType, boolean value) {
        if (value) {
            addTransportType(transportType);
        } else {
            removeTransportType(transportType);
        }
        return this;
    }

    /**
     * Gets all the transports set on this {@code NetworkCapability} instance.
     *
     * @return an array of transport type values for this instance.
     * @hide
     */
    @TestApi
    public @Transport int[] getTransportTypes() {
        return BitUtils.unpackBits(mTransportTypes);
    }

    /**
     * Sets all the transports set on this {@code NetworkCapability} instance.
     * This overwrites any existing transports.
     *
     * @hide
     */
    public void setTransportTypes(@Transport int[] transportTypes) {
        mTransportTypes = BitUtils.packBits(transportTypes);
    }

    /**
     * Tests for the presence of a transport on this instance.
     *
     * @param transportType the transport type to be tested for.
     * @return {@code true} if set on this instance.
     */
    public boolean hasTransport(@Transport int transportType) {
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
     * UID of the app that manages this network, or INVALID_UID if none/unknown.
     *
     * This field keeps track of the UID of the app that created this network and is in charge
     * of managing it. In the practice, it is used to store the UID of VPN apps so it is named
     * accordingly, but it may be renamed if other mechanisms are offered for third party apps
     * to create networks.
     *
     * Because this field is only used in the services side (and to avoid apps being able to
     * set this to whatever they want), this field is not parcelled and will not be conserved
     * across the IPC boundary.
     * @hide
     */
    private int mEstablishingVpnAppUid = INVALID_UID;

    /**
     * Set the UID of the managing app.
     * @hide
     */
    public void setEstablishingVpnAppUid(final int uid) {
        mEstablishingVpnAppUid = uid;
    }

    /**
     * Value indicating that link bandwidth is unspecified.
     * @hide
     */
    public static final int LINK_BANDWIDTH_UNSPECIFIED = 0;

    /**
     * Passive link bandwidth.  This is a rough guide of the expected peak bandwidth
     * for the first hop on the given transport.  It is not measured, but may take into account
     * link parameters (Radio technology, allocated channels, etc).
     */
    private int mLinkUpBandwidthKbps = LINK_BANDWIDTH_UNSPECIFIED;
    private int mLinkDownBandwidthKbps = LINK_BANDWIDTH_UNSPECIFIED;

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
    public NetworkCapabilities setLinkUpstreamBandwidthKbps(int upKbps) {
        mLinkUpBandwidthKbps = upKbps;
        return this;
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
    public NetworkCapabilities setLinkDownstreamBandwidthKbps(int downKbps) {
        mLinkDownBandwidthKbps = downKbps;
        return this;
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
    /** @hide */
    public static int minBandwidth(int a, int b) {
        if (a == LINK_BANDWIDTH_UNSPECIFIED)  {
            return b;
        } else if (b == LINK_BANDWIDTH_UNSPECIFIED) {
            return a;
        } else {
            return Math.min(a, b);
        }
    }
    /** @hide */
    public static int maxBandwidth(int a, int b) {
        return Math.max(a, b);
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
    @UnsupportedAppUsage
    private int mSignalStrength = SIGNAL_STRENGTH_UNSPECIFIED;

    /**
     * Sets the signal strength. This is a signed integer, with higher values indicating a stronger
     * signal. The exact units are bearer-dependent. For example, Wi-Fi uses the same RSSI units
     * reported by wifi code.
     * <p>
     * Note that when used to register a network callback, this specifies the minimum acceptable
     * signal strength. When received as the state of an existing network it specifies the current
     * value. A value of code SIGNAL_STRENGTH_UNSPECIFIED} means no value when received and has no
     * effect when requesting a callback.
     *
     * @param signalStrength the bearer-specific signal strength.
     * @hide
     */
    @UnsupportedAppUsage
    public NetworkCapabilities setSignalStrength(int signalStrength) {
        mSignalStrength = signalStrength;
        return this;
    }

    /**
     * Returns {@code true} if this object specifies a signal strength.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public boolean hasSignalStrength() {
        return mSignalStrength > SIGNAL_STRENGTH_UNSPECIFIED;
    }

    /**
     * Retrieves the signal strength.
     *
     * @return The bearer-specific signal strength.
     * @hide
     */
    @SystemApi
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
     * List of UIDs this network applies to. No restriction if null.
     * <p>
     * For networks, mUids represent the list of network this applies to, and null means this
     * network applies to all UIDs.
     * For requests, mUids is the list of UIDs this network MUST apply to to match ; ALL UIDs
     * must be included in a network so that they match. As an exception to the general rule,
     * a null mUids field for requests mean "no requirements" rather than what the general rule
     * would suggest ("must apply to all UIDs") : this is because this has shown to be what users
     * of this API expect in practice. A network that must match all UIDs can still be
     * expressed with a set ranging the entire set of possible UIDs.
     * <p>
     * mUids is typically (and at this time, only) used by VPN. This network is only available to
     * the UIDs in this list, and it is their default network. Apps in this list that wish to
     * bypass the VPN can do so iff the VPN app allows them to or if they are privileged. If this
     * member is null, then the network is not restricted by app UID. If it's an empty list, then
     * it means nobody can use it.
     * As a special exception, the app managing this network (as identified by its UID stored in
     * mEstablishingVpnAppUid) can always see this network. This is embodied by a special check in
     * satisfiedByUids. That still does not mean the network necessarily <strong>applies</strong>
     * to the app that manages it as determined by #appliesToUid.
     * <p>
     * Please note that in principle a single app can be associated with multiple UIDs because
     * each app will have a different UID when it's run as a different (macro-)user. A single
     * macro user can only have a single active VPN app at any given time however.
     * <p>
     * Also please be aware this class does not try to enforce any normalization on this. Callers
     * can only alter the UIDs by setting them wholesale : this class does not provide any utility
     * to add or remove individual UIDs or ranges. If callers have any normalization needs on
     * their own (like requiring sortedness or no overlap) they need to enforce it
     * themselves. Some of the internal methods also assume this is normalized as in no adjacent
     * or overlapping ranges are present.
     *
     * @hide
     */
    private ArraySet<UidRange> mUids = null;

    /**
     * Convenience method to set the UIDs this network applies to to a single UID.
     * @hide
     */
    public NetworkCapabilities setSingleUid(int uid) {
        final ArraySet<UidRange> identity = new ArraySet<>(1);
        identity.add(new UidRange(uid, uid));
        setUids(identity);
        return this;
    }

    /**
     * Set the list of UIDs this network applies to.
     * This makes a copy of the set so that callers can't modify it after the call.
     * @hide
     */
    public NetworkCapabilities setUids(Set<UidRange> uids) {
        if (null == uids) {
            mUids = null;
        } else {
            mUids = new ArraySet<>(uids);
        }
        return this;
    }

    /**
     * Get the list of UIDs this network applies to.
     * This returns a copy of the set so that callers can't modify the original object.
     * @hide
     */
    public Set<UidRange> getUids() {
        return null == mUids ? null : new ArraySet<>(mUids);
    }

    /**
     * Test whether this network applies to this UID.
     * @hide
     */
    public boolean appliesToUid(int uid) {
        if (null == mUids) return true;
        for (UidRange range : mUids) {
            if (range.contains(uid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests if the set of UIDs that this network applies to is the same as the passed network.
     * <p>
     * This test only checks whether equal range objects are in both sets. It will
     * return false if the ranges are not exactly the same, even if the covered UIDs
     * are for an equivalent result.
     * <p>
     * Note that this method is not very optimized, which is fine as long as it's not used very
     * often.
     * <p>
     * nc is assumed nonnull.
     *
     * @hide
     */
    @VisibleForTesting
    public boolean equalsUids(NetworkCapabilities nc) {
        Set<UidRange> comparedUids = nc.mUids;
        if (null == comparedUids) return null == mUids;
        if (null == mUids) return false;
        // Make a copy so it can be mutated to check that all ranges in mUids
        // also are in uids.
        final Set<UidRange> uids = new ArraySet<>(mUids);
        for (UidRange range : comparedUids) {
            if (!uids.contains(range)) {
                return false;
            }
            uids.remove(range);
        }
        return uids.isEmpty();
    }

    /**
     * Test whether the passed NetworkCapabilities satisfies the UIDs this capabilities require.
     *
     * This method is called on the NetworkCapabilities embedded in a request with the
     * capabilities of an available network. It checks whether all the UIDs from this listen
     * (representing the UIDs that must have access to the network) are satisfied by the UIDs
     * in the passed nc (representing the UIDs that this network is available to).
     * <p>
     * As a special exception, the UID that created the passed network (as represented by its
     * mEstablishingVpnAppUid field) always satisfies a NetworkRequest requiring it (of LISTEN
     * or REQUEST types alike), even if the network does not apply to it. That is so a VPN app
     * can see its own network when it listens for it.
     * <p>
     * nc is assumed nonnull. Else, NPE.
     * @see #appliesToUid
     * @hide
     */
    public boolean satisfiedByUids(NetworkCapabilities nc) {
        if (null == nc.mUids || null == mUids) return true; // The network satisfies everything.
        for (UidRange requiredRange : mUids) {
            if (requiredRange.contains(nc.mEstablishingVpnAppUid)) return true;
            if (!nc.appliesToUidRange(requiredRange)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether this network applies to the passed ranges.
     * This assumes that to apply, the passed range has to be entirely contained
     * within one of the ranges this network applies to. If the ranges are not normalized,
     * this method may return false even though all required UIDs are covered because no
     * single range contained them all.
     * @hide
     */
    @VisibleForTesting
    public boolean appliesToUidRange(UidRange requiredRange) {
        if (null == mUids) return true;
        for (UidRange uidRange : mUids) {
            if (uidRange.containsRange(requiredRange)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Combine the UIDs this network currently applies to with the UIDs the passed
     * NetworkCapabilities apply to.
     * nc is assumed nonnull.
     */
    private void combineUids(NetworkCapabilities nc) {
        if (null == nc.mUids || null == mUids) {
            mUids = null;
            return;
        }
        mUids.addAll(nc.mUids);
    }


    /**
     * The SSID of the network, or null if not applicable or unknown.
     * <p>
     * This is filled in by wifi code.
     * @hide
     */
    private String mSSID;

    /**
     * Sets the SSID of this network.
     * @hide
     */
    public NetworkCapabilities setSSID(String ssid) {
        mSSID = ssid;
        return this;
    }

    /**
     * Gets the SSID of this network, or null if none or unknown.
     * @hide
     */
    public String getSSID() {
        return mSSID;
    }

    /**
     * Tests if the SSID of this network is the same as the SSID of the passed network.
     * @hide
     */
    public boolean equalsSSID(NetworkCapabilities nc) {
        return Objects.equals(mSSID, nc.mSSID);
    }

    /**
     * Check if the SSID requirements of this object are matched by the passed object.
     * @hide
     */
    public boolean satisfiedBySSID(NetworkCapabilities nc) {
        return mSSID == null || mSSID.equals(nc.mSSID);
    }

    /**
     * Combine SSIDs of the capabilities.
     * <p>
     * This is only legal if either the SSID of this object is null, or both SSIDs are
     * equal.
     * @hide
     */
    private void combineSSIDs(NetworkCapabilities nc) {
        if (mSSID != null && !mSSID.equals(nc.mSSID)) {
            throw new IllegalStateException("Can't combine two SSIDs");
        }
        setSSID(nc.mSSID);
    }

    /**
     * Combine a set of Capabilities to this one.  Useful for coming up with the complete set.
     * <p>
     * Note that this method may break an invariant of having a particular capability in either
     * wanted or unwanted lists but never in both.  Requests that have the same capability in
     * both lists will never be satisfied.
     * @hide
     */
    public void combineCapabilities(NetworkCapabilities nc) {
        combineNetCapabilities(nc);
        combineTransportTypes(nc);
        combineLinkBandwidths(nc);
        combineSpecifiers(nc);
        combineSignalStrength(nc);
        combineUids(nc);
        combineSSIDs(nc);
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
        return (nc != null
                && satisfiedByNetCapabilities(nc, onlyImmutable)
                && satisfiedByTransportTypes(nc)
                && (onlyImmutable || satisfiedByLinkBandwidths(nc))
                && satisfiedBySpecifier(nc)
                && (onlyImmutable || satisfiedBySignalStrength(nc))
                && (onlyImmutable || satisfiedByUids(nc))
                && (onlyImmutable || satisfiedBySSID(nc)));
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
        final long mask = ~MUTABLE_CAPABILITIES & ~(1 << NET_CAPABILITY_NOT_METERED);
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
        NetworkCapabilities that = (NetworkCapabilities) obj;
        return (equalsNetCapabilities(that)
                && equalsTransportTypes(that)
                && equalsLinkBandwidths(that)
                && equalsSignalStrength(that)
                && equalsSpecifier(that)
                && equalsUids(that)
                && equalsSSID(that));
    }

    @Override
    public int hashCode() {
        return (int) (mNetworkCapabilities & 0xFFFFFFFF)
                + ((int) (mNetworkCapabilities >> 32) * 3)
                + ((int) (mUnwantedNetworkCapabilities & 0xFFFFFFFF) * 5)
                + ((int) (mUnwantedNetworkCapabilities >> 32) * 7)
                + ((int) (mTransportTypes & 0xFFFFFFFF) * 11)
                + ((int) (mTransportTypes >> 32) * 13)
                + (mLinkUpBandwidthKbps * 17)
                + (mLinkDownBandwidthKbps * 19)
                + Objects.hashCode(mNetworkSpecifier) * 23
                + (mSignalStrength * 29)
                + Objects.hashCode(mUids) * 31
                + Objects.hashCode(mSSID) * 37;
    }

    @Override
    public int describeContents() {
        return 0;
    }
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mNetworkCapabilities);
        dest.writeLong(mUnwantedNetworkCapabilities);
        dest.writeLong(mTransportTypes);
        dest.writeInt(mLinkUpBandwidthKbps);
        dest.writeInt(mLinkDownBandwidthKbps);
        dest.writeParcelable((Parcelable) mNetworkSpecifier, flags);
        dest.writeInt(mSignalStrength);
        dest.writeArraySet(mUids);
        dest.writeString(mSSID);
    }

    public static final Creator<NetworkCapabilities> CREATOR =
        new Creator<NetworkCapabilities>() {
            @Override
            public NetworkCapabilities createFromParcel(Parcel in) {
                NetworkCapabilities netCap = new NetworkCapabilities();

                netCap.mNetworkCapabilities = in.readLong();
                netCap.mUnwantedNetworkCapabilities = in.readLong();
                netCap.mTransportTypes = in.readLong();
                netCap.mLinkUpBandwidthKbps = in.readInt();
                netCap.mLinkDownBandwidthKbps = in.readInt();
                netCap.mNetworkSpecifier = in.readParcelable(null);
                netCap.mSignalStrength = in.readInt();
                netCap.mUids = (ArraySet<UidRange>) in.readArraySet(
                        null /* ClassLoader, null for default */);
                netCap.mSSID = in.readString();
                return netCap;
            }
            @Override
            public NetworkCapabilities[] newArray(int size) {
                return new NetworkCapabilities[size];
            }
        };

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("[");
        if (0 != mTransportTypes) {
            sb.append(" Transports: ");
            appendStringRepresentationOfBitMaskToStringBuilder(sb, mTransportTypes,
                    NetworkCapabilities::transportNameOf, "|");
        }
        if (0 != mNetworkCapabilities) {
            sb.append(" Capabilities: ");
            appendStringRepresentationOfBitMaskToStringBuilder(sb, mNetworkCapabilities,
                    NetworkCapabilities::capabilityNameOf, "&");
        }
        if (0 != mNetworkCapabilities) {
            sb.append(" Unwanted: ");
            appendStringRepresentationOfBitMaskToStringBuilder(sb, mUnwantedNetworkCapabilities,
                    NetworkCapabilities::capabilityNameOf, "&");
        }
        if (mLinkUpBandwidthKbps > 0) {
            sb.append(" LinkUpBandwidth>=").append(mLinkUpBandwidthKbps).append("Kbps");
        }
        if (mLinkDownBandwidthKbps > 0) {
            sb.append(" LinkDnBandwidth>=").append(mLinkDownBandwidthKbps).append("Kbps");
        }
        if (mNetworkSpecifier != null) {
            sb.append(" Specifier: <").append(mNetworkSpecifier).append(">");
        }
        if (hasSignalStrength()) {
            sb.append(" SignalStrength: ").append(mSignalStrength);
        }

        if (null != mUids) {
            if ((1 == mUids.size()) && (mUids.valueAt(0).count() == 1)) {
                sb.append(" Uid: ").append(mUids.valueAt(0).start);
            } else {
                sb.append(" Uids: <").append(mUids).append(">");
            }
        }
        if (mEstablishingVpnAppUid != INVALID_UID) {
            sb.append(" EstablishingAppUid: ").append(mEstablishingVpnAppUid);
        }

        if (null != mSSID) {
            sb.append(" SSID: ").append(mSSID);
        }

        sb.append("]");
        return sb.toString();
    }


    private interface NameOf {
        String nameOf(int value);
    }
    /**
     * @hide
     */
    public static void appendStringRepresentationOfBitMaskToStringBuilder(StringBuilder sb,
            long bitMask, NameOf nameFetcher, String separator) {
        int bitPos = 0;
        boolean firstElementAdded = false;
        while (bitMask != 0) {
            if ((bitMask & 1) != 0) {
                if (firstElementAdded) {
                    sb.append(separator);
                } else {
                    firstElementAdded = true;
                }
                sb.append(nameFetcher.nameOf(bitPos));
            }
            bitMask >>= 1;
            ++bitPos;
        }
    }

    /** @hide */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        for (int transport : getTransportTypes()) {
            proto.write(NetworkCapabilitiesProto.TRANSPORTS, transport);
        }

        for (int capability : getCapabilities()) {
            proto.write(NetworkCapabilitiesProto.CAPABILITIES, capability);
        }

        proto.write(NetworkCapabilitiesProto.LINK_UP_BANDWIDTH_KBPS, mLinkUpBandwidthKbps);
        proto.write(NetworkCapabilitiesProto.LINK_DOWN_BANDWIDTH_KBPS, mLinkDownBandwidthKbps);

        if (mNetworkSpecifier != null) {
            proto.write(NetworkCapabilitiesProto.NETWORK_SPECIFIER, mNetworkSpecifier.toString());
        }

        proto.write(NetworkCapabilitiesProto.CAN_REPORT_SIGNAL_STRENGTH, hasSignalStrength());
        proto.write(NetworkCapabilitiesProto.SIGNAL_STRENGTH, mSignalStrength);

        proto.end(token);
    }

    /**
     * @hide
     */
    public static String capabilityNamesOf(@NetCapability int[] capabilities) {
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
    public static String capabilityNameOf(@NetCapability int capability) {
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
            case NET_CAPABILITY_NOT_ROAMING:    return "NOT_ROAMING";
            case NET_CAPABILITY_FOREGROUND:     return "FOREGROUND";
            case NET_CAPABILITY_NOT_CONGESTED:  return "NOT_CONGESTED";
            case NET_CAPABILITY_NOT_SUSPENDED:  return "NOT_SUSPENDED";
            case NET_CAPABILITY_OEM_PAID:       return "OEM_PAID";
            default:                            return Integer.toString(capability);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static String transportNamesOf(@Transport int[] types) {
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
    public static String transportNameOf(@Transport int transport) {
        if (!isValidTransport(transport)) {
            return "UNKNOWN";
        }
        return TRANSPORT_NAMES[transport];
    }

    private static void checkValidTransportType(@Transport int transport) {
        Preconditions.checkArgument(
                isValidTransport(transport), "Invalid TransportType " + transport);
    }

    private static boolean isValidCapability(@NetworkCapabilities.NetCapability int capability) {
        return capability >= MIN_NET_CAPABILITY && capability <= MAX_NET_CAPABILITY;
    }

    private static void checkValidCapability(@NetworkCapabilities.NetCapability int capability) {
        Preconditions.checkArgument(isValidCapability(capability),
                "NetworkCapability " + capability + "out of range");
    }

    /**
     * Check if this {@code NetworkCapability} instance is metered.
     *
     * @return {@code true} if {@code NET_CAPABILITY_NOT_METERED} is not set on this instance.
     * @hide
     */
    public boolean isMetered() {
        return !hasCapability(NET_CAPABILITY_NOT_METERED);
    }
}
