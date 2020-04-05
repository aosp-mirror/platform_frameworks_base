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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.ConnectivityManager.NetworkCallback;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.BitUtils;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
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

    // Set to true when private DNS is broken.
    private boolean mPrivateDnsBroken;

    /**
     * Uid of the app making the request.
     */
    private int mRequestorUid;

    /**
     * Package name of the app making the request.
     */
    private String mRequestorPackageName;

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
        mTransportInfo = null;
        mSignalStrength = SIGNAL_STRENGTH_UNSPECIFIED;
        mUids = null;
        mAdministratorUids = new int[0];
        mOwnerUid = Process.INVALID_UID;
        mSSID = null;
        mPrivateDnsBroken = false;
        mRequestorUid = Process.INVALID_UID;
        mRequestorPackageName = null;
    }

    /**
     * Set all contents of this object to the contents of a NetworkCapabilities.
     * @hide
     */
    public void set(@NonNull NetworkCapabilities nc) {
        mNetworkCapabilities = nc.mNetworkCapabilities;
        mTransportTypes = nc.mTransportTypes;
        mLinkUpBandwidthKbps = nc.mLinkUpBandwidthKbps;
        mLinkDownBandwidthKbps = nc.mLinkDownBandwidthKbps;
        mNetworkSpecifier = nc.mNetworkSpecifier;
        mTransportInfo = nc.mTransportInfo;
        mSignalStrength = nc.mSignalStrength;
        setUids(nc.mUids); // Will make the defensive copy
        setAdministratorUids(nc.getAdministratorUids());
        mOwnerUid = nc.mOwnerUid;
        mUnwantedNetworkCapabilities = nc.mUnwantedNetworkCapabilities;
        mSSID = nc.mSSID;
        mPrivateDnsBroken = nc.mPrivateDnsBroken;
        mRequestorUid = nc.mRequestorUid;
        mRequestorPackageName = nc.mRequestorPackageName;
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
            NET_CAPABILITY_MCX,
            NET_CAPABILITY_PARTIAL_CONNECTIVITY,
            NET_CAPABILITY_TEMPORARILY_NOT_METERED,
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

    /**
     * Indicates this is a network that has the ability to reach a carrier's Mission Critical
     * servers.
     */
    public static final int NET_CAPABILITY_MCX = 23;

    /**
     * Indicates that this network was tested to only provide partial connectivity.
     * @hide
     */
    @SystemApi
    public static final int NET_CAPABILITY_PARTIAL_CONNECTIVITY = 24;

    /**
     * This capability will be set for networks that are generally metered, but are currently
     * unmetered, e.g., because the user is in a particular area. This capability can be changed at
     * any time. When it is removed, applications are responsible for stopping any data transfer
     * that should not occur on a metered network.
     */
    public static final int NET_CAPABILITY_TEMPORARILY_NOT_METERED = 25;

    private static final int MIN_NET_CAPABILITY = NET_CAPABILITY_MMS;
    private static final int MAX_NET_CAPABILITY = NET_CAPABILITY_TEMPORARILY_NOT_METERED;

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
            | (1 << NET_CAPABILITY_NOT_SUSPENDED)
            | (1 << NET_CAPABILITY_PARTIAL_CONNECTIVITY
            | (1 << NET_CAPABILITY_TEMPORARILY_NOT_METERED));

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
            (1 << NET_CAPABILITY_XCAP) |
            (1 << NET_CAPABILITY_MCX);

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
     * Capabilities that are managed by ConnectivityService.
     */
    private static final long CONNECTIVITY_MANAGED_CAPABILITIES =
            (1 << NET_CAPABILITY_VALIDATED)
            | (1 << NET_CAPABILITY_CAPTIVE_PORTAL)
            | (1 << NET_CAPABILITY_FOREGROUND)
            | (1 << NET_CAPABILITY_PARTIAL_CONNECTIVITY);

    /**
     * Capabilities that are allowed for test networks. This list must be set so that it is safe
     * for an unprivileged user to create a network with these capabilities via shell. As such,
     * it must never contain capabilities that are generally useful to the system, such as
     * INTERNET, IMS, SUPL, etc.
     */
    private static final long TEST_NETWORKS_ALLOWED_CAPABILITIES =
            (1 << NET_CAPABILITY_NOT_METERED)
            | (1 << NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            | (1 << NET_CAPABILITY_NOT_RESTRICTED)
            | (1 << NET_CAPABILITY_NOT_VPN)
            | (1 << NET_CAPABILITY_NOT_ROAMING)
            | (1 << NET_CAPABILITY_NOT_CONGESTED)
            | (1 << NET_CAPABILITY_NOT_SUSPENDED);

    /**
     * Adds the given capability to this {@code NetworkCapability} instance.
     * Note that when searching for a network to satisfy a request, all capabilities
     * requested must be satisfied.
     *
     * @param capability the capability to be added.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public @NonNull NetworkCapabilities addCapability(@NetCapability int capability) {
        // If the given capability was previously added to the list of unwanted capabilities
        // then the capability will also be removed from the list of unwanted capabilities.
        // TODO: Consider adding unwanted capabilities to the public API and mention this
        // in the documentation.
        checkValidCapability(capability);
        mNetworkCapabilities |= 1 << capability;
        mUnwantedNetworkCapabilities &= ~(1 << capability);  // remove from unwanted capability list
        return this;
    }

    /**
     * Adds the given capability to the list of unwanted capabilities of this
     * {@code NetworkCapability} instance. Note that when searching for a network to
     * satisfy a request, the network must not contain any capability from unwanted capability
     * list.
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
     *
     * @param capability the capability to be removed.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public @NonNull NetworkCapabilities removeCapability(@NetCapability int capability) {
        // Note that this method removes capabilities that were added via addCapability(int),
        // addUnwantedCapability(int) or setCapabilities(int[], int[]).
        checkValidCapability(capability);
        final long mask = ~(1 << capability);
        mNetworkCapabilities &= mask;
        mUnwantedNetworkCapabilities &= mask;
        return this;
    }

    /**
     * Sets (or clears) the given capability on this {@link NetworkCapabilities}
     * instance.
     * @hide
     */
    public @NonNull NetworkCapabilities setCapability(@NetCapability int capability,
            boolean value) {
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
    @UnsupportedAppUsage
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

    /**
     * Check if this NetworkCapabilities has system managed capabilities or not.
     * @hide
     */
    public boolean hasConnectivityManagedCapability() {
        return ((mNetworkCapabilities & CONNECTIVITY_MANAGED_CAPABILITIES) != 0);
    }

    /** Note this method may result in having the same capability in wanted and unwanted lists. */
    private void combineNetCapabilities(@NonNull NetworkCapabilities nc) {
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
    public @Nullable String describeFirstNonRequestableCapability() {
        final long nonRequestable = (mNetworkCapabilities | mUnwantedNetworkCapabilities)
                & NON_REQUESTABLE_CAPABILITIES;

        if (nonRequestable != 0) {
            return capabilityNameOf(BitUtils.unpackBits(nonRequestable)[0]);
        }
        if (mLinkUpBandwidthKbps != 0 || mLinkDownBandwidthKbps != 0) return "link bandwidth";
        if (hasSignalStrength()) return "signalStrength";
        if (isPrivateDnsBroken()) {
            return "privateDnsBroken";
        }
        return null;
    }

    private boolean satisfiedByNetCapabilities(@NonNull NetworkCapabilities nc,
            boolean onlyImmutable) {
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
    public boolean equalsNetCapabilities(@NonNull NetworkCapabilities nc) {
        return (nc.mNetworkCapabilities == this.mNetworkCapabilities)
                && (nc.mUnwantedNetworkCapabilities == this.mUnwantedNetworkCapabilities);
    }

    private boolean equalsNetCapabilitiesRequestable(@NonNull NetworkCapabilities that) {
        return ((this.mNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES) ==
                (that.mNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES))
                && ((this.mUnwantedNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES) ==
                (that.mUnwantedNetworkCapabilities & ~NON_REQUESTABLE_CAPABILITIES));
    }

    /**
     * Deduces that all the capabilities it provides are typically provided by restricted networks
     * or not.
     *
     * @return {@code true} if the network should be restricted.
     * @hide
     */
    public boolean deduceRestrictedCapability() {
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

        return forceRestrictedCapability
                || (hasRestrictedCapabilities && !hasUnrestrictedCapabilities);
    }

    /**
     * Removes the NET_CAPABILITY_NOT_RESTRICTED capability if deducing the network is restricted.
     *
     * @hide
     */
    public void maybeMarkCapabilitiesRestricted() {
        if (deduceRestrictedCapability()) {
            removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
        }
    }

    /**
     * Test networks have strong restrictions on what capabilities they can have. Enforce these
     * restrictions.
     * @hide
     */
    public void restrictCapabilitesForTestNetwork() {
        final long originalCapabilities = mNetworkCapabilities;
        final NetworkSpecifier originalSpecifier = mNetworkSpecifier;
        clearAll();
        // Reset the transports to only contain TRANSPORT_TEST.
        mTransportTypes = (1 << TRANSPORT_TEST);
        mNetworkCapabilities = originalCapabilities & TEST_NETWORKS_ALLOWED_CAPABILITIES;
        mNetworkSpecifier = originalSpecifier;
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
            TRANSPORT_TEST,
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

    /**
     * Indicates this network uses a Test-only virtual interface as a transport.
     *
     * @hide
     */
    @TestApi
    public static final int TRANSPORT_TEST = 7;

    /** @hide */
    public static final int MIN_TRANSPORT = TRANSPORT_CELLULAR;
    /** @hide */
    public static final int MAX_TRANSPORT = TRANSPORT_TEST;

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
        "LOWPAN",
        "TEST"
    };

    /**
     * Adds the given transport type to this {@code NetworkCapability} instance.
     * Multiple transports may be applied.  Note that when searching
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
    public @NonNull NetworkCapabilities addTransportType(@Transport int transportType) {
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
    public @NonNull NetworkCapabilities removeTransportType(@Transport int transportType) {
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
    public @NonNull NetworkCapabilities setTransportType(@Transport int transportType,
            boolean value) {
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
    @SystemApi
    @NonNull public @Transport int[] getTransportTypes() {
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
     * UID of the app that owns this network, or Process#INVALID_UID if none/unknown.
     *
     * <p>This field keeps track of the UID of the app that created this network and is in charge of
     * its lifecycle. This could be the UID of apps such as the Wifi network suggestor, the running
     * VPN, or Carrier Service app managing a cellular data connection.
     *
     * <p>For NetworkCapability instances being sent from ConnectivityService, this value MUST be
     * reset to Process.INVALID_UID unless all the following conditions are met:
     *
     * <ol>
     *   <li>The destination app is the network owner
     *   <li>The destination app has the ACCESS_FINE_LOCATION permission granted
     *   <li>The user's location toggle is on
     * </ol>
     *
     * This is because the owner UID is location-sensitive. The apps that request a network could
     * know where the device is if they can tell for sure the system has connected to the network
     * they requested.
     *
     * <p>This is populated by the network agents and for the NetworkCapabilities instance sent by
     * an app to the System Server, the value MUST be reset to Process.INVALID_UID by the system
     * server.
     */
    private int mOwnerUid = Process.INVALID_UID;

    /**
     * Set the UID of the owner app.
     * @hide
     */
    public @NonNull NetworkCapabilities setOwnerUid(final int uid) {
        mOwnerUid = uid;
        return this;
    }

    /**
     * Retrieves the UID of the app that owns this network.
     *
     * <p>For user privacy reasons, this field will only be populated if:
     *
     * <ol>
     *   <li>The calling app is the network owner
     *   <li>The calling app has the ACCESS_FINE_LOCATION permission granted
     *   <li>The user's location toggle is on
     * </ol>
     *
     * Instances of NetworkCapabilities sent to apps without the appropriate permissions will
     * have this field cleared out.
     */
    public int getOwnerUid() {
        return mOwnerUid;
    }

    /**
     * UIDs of packages that are administrators of this network, or empty if none.
     *
     * <p>This field tracks the UIDs of packages that have permission to manage this network.
     *
     * <p>Network owners will also be listed as administrators.
     *
     * <p>For NetworkCapability instances being sent from the System Server, this value MUST be
     * empty unless the destination is 1) the System Server, or 2) Telephony. In either case, the
     * receiving entity must have the ACCESS_FINE_LOCATION permission and target R+.
     *
     * <p>When received from an app in a NetworkRequest this is always cleared out by the system
     * server. This field is never used for matching NetworkRequests to NetworkAgents.
     */
    @NonNull private int[] mAdministratorUids = new int[0];

    /**
     * Sets the int[] of UIDs that are administrators of this network.
     *
     * <p>UIDs included in administratorUids gain administrator privileges over this Network.
     * Examples of UIDs that should be included in administratorUids are:
     *
     * <ul>
     *   <li>Carrier apps with privileges for the relevant subscription
     *   <li>Active VPN apps
     *   <li>Other application groups with a particular Network-related role
     * </ul>
     *
     * <p>In general, user-supplied networks (such as WiFi networks) do not have an administrator.
     *
     * <p>An app is granted owner privileges over Networks that it supplies. The owner UID MUST
     * always be included in administratorUids.
     *
     * <p>The administrator UIDs are set by network agents.
     *
     * @param administratorUids the UIDs to be set as administrators of this Network.
     * @throws IllegalArgumentException if duplicate UIDs are contained in administratorUids
     * @see #mAdministratorUids
     * @hide
     */
    @NonNull
    public NetworkCapabilities setAdministratorUids(@NonNull final int[] administratorUids) {
        mAdministratorUids = Arrays.copyOf(administratorUids, administratorUids.length);
        Arrays.sort(mAdministratorUids);
        for (int i = 0; i < mAdministratorUids.length - 1; i++) {
            if (mAdministratorUids[i] >= mAdministratorUids[i + 1]) {
                throw new IllegalArgumentException("All administrator UIDs must be unique");
            }
        }
        return this;
    }

    /**
     * Retrieves the UIDs that are administrators of this Network.
     *
     * <p>This is only populated in NetworkCapabilities objects that come from network agents for
     * networks that are managed by specific apps on the system, such as carrier privileged apps or
     * wifi suggestion apps. This will include the network owner.
     *
     * @return the int[] of UIDs that are administrators of this Network
     * @see #mAdministratorUids
     * @hide
     */
    @NonNull
    @SystemApi
    @TestApi
    public int[] getAdministratorUids() {
        return Arrays.copyOf(mAdministratorUids, mAdministratorUids.length);
    }

    /**
     * Tests if the set of administrator UIDs of this network is the same as that of the passed one.
     *
     * <p>The administrator UIDs must be in sorted order.
     *
     * <p>nc is assumed non-null. Else, NPE.
     *
     * @hide
     */
    @VisibleForTesting(visibility = PRIVATE)
    public boolean equalsAdministratorUids(@NonNull final NetworkCapabilities nc) {
        return Arrays.equals(mAdministratorUids, nc.mAdministratorUids);
    }

    /**
     * Combine the administrator UIDs of the capabilities.
     *
     * <p>This is only legal if either of the administrators lists are empty, or if they are equal.
     * Combining administrator UIDs is only possible for combining non-overlapping sets of UIDs.
     *
     * <p>If both administrator lists are non-empty but not equal, they conflict with each other. In
     * this case, it would not make sense to add them together.
     */
    private void combineAdministratorUids(@NonNull final NetworkCapabilities nc) {
        if (nc.mAdministratorUids.length == 0) return;
        if (mAdministratorUids.length == 0) {
            mAdministratorUids = Arrays.copyOf(nc.mAdministratorUids, nc.mAdministratorUids.length);
            return;
        }
        if (!equalsAdministratorUids(nc)) {
            throw new IllegalStateException("Can't combine two different administrator UID lists");
        }
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
     * {@see Builder#setLinkUpstreamBandwidthKbps}
     *
     * @param upKbps the estimated first hop upstream (device to network) bandwidth.
     * @hide
     */
    public @NonNull NetworkCapabilities setLinkUpstreamBandwidthKbps(int upKbps) {
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
     * {@see Builder#setLinkUpstreamBandwidthKbps}
     *
     * @param downKbps the estimated first hop downstream (network to device) bandwidth.
     * @hide
     */
    public @NonNull NetworkCapabilities setLinkDownstreamBandwidthKbps(int downKbps) {
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
    private TransportInfo mTransportInfo = null;

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
    public @NonNull NetworkCapabilities setNetworkSpecifier(
            @NonNull NetworkSpecifier networkSpecifier) {
        if (networkSpecifier != null && Long.bitCount(mTransportTypes) != 1) {
            throw new IllegalStateException("Must have a single transport specified to use " +
                    "setNetworkSpecifier");
        }

        mNetworkSpecifier = networkSpecifier;

        return this;
    }

    /**
     * Sets the optional transport specific information.
     *
     * @param transportInfo A concrete, parcelable framework class that extends
     * {@link TransportInfo}.
     * @return This NetworkCapabilities instance, to facilitate chaining.
     * @hide
     */
    public @NonNull NetworkCapabilities setTransportInfo(@NonNull TransportInfo transportInfo) {
        mTransportInfo = transportInfo;
        return this;
    }

    /**
     * Gets the optional bearer specific network specifier. May be {@code null} if not set.
     *
     * @return The optional {@link NetworkSpecifier} specifying the bearer specific network
     *         specifier or {@code null}.
     */
    public @Nullable NetworkSpecifier getNetworkSpecifier() {
        return mNetworkSpecifier;
    }

    /**
     * Returns a transport-specific information container. The application may cast this
     * container to a concrete sub-class based on its knowledge of the network request. The
     * application should be able to deal with a {@code null} return value or an invalid case,
     * e.g. use {@code instanceof} operator to verify expected type.
     *
     * @return A concrete implementation of the {@link TransportInfo} class or null if not
     * available for the network.
     */
    @Nullable public TransportInfo getTransportInfo() {
        return mTransportInfo;
    }

    private void combineSpecifiers(NetworkCapabilities nc) {
        if (mNetworkSpecifier != null && !mNetworkSpecifier.equals(nc.mNetworkSpecifier)) {
            throw new IllegalStateException("Can't combine two networkSpecifiers");
        }
        setNetworkSpecifier(nc.mNetworkSpecifier);
    }

    private boolean satisfiedBySpecifier(NetworkCapabilities nc) {
        return mNetworkSpecifier == null || mNetworkSpecifier.canBeSatisfiedBy(nc.mNetworkSpecifier)
                || nc.mNetworkSpecifier instanceof MatchAllNetworkSpecifier;
    }

    private boolean equalsSpecifier(NetworkCapabilities nc) {
        return Objects.equals(mNetworkSpecifier, nc.mNetworkSpecifier);
    }

    private void combineTransportInfos(NetworkCapabilities nc) {
        if (mTransportInfo != null && !mTransportInfo.equals(nc.mTransportInfo)) {
            throw new IllegalStateException("Can't combine two TransportInfos");
        }
        setTransportInfo(nc.mTransportInfo);
    }

    private boolean equalsTransportInfo(NetworkCapabilities nc) {
        return Objects.equals(mTransportInfo, nc.mTransportInfo);
    }

    /**
     * Magic value that indicates no signal strength provided. A request specifying this value is
     * always satisfied.
     */
    public static final int SIGNAL_STRENGTH_UNSPECIFIED = Integer.MIN_VALUE;

    /**
     * Signal strength. This is a signed integer, and higher values indicate better signal.
     * The exact units are bearer-dependent. For example, Wi-Fi uses RSSI.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
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
    public @NonNull NetworkCapabilities setSignalStrength(int signalStrength) {
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
     * mOwnerUid) can always see this network. This is embodied by a special check in
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
    public @NonNull NetworkCapabilities setSingleUid(int uid) {
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
    public @NonNull NetworkCapabilities setUids(Set<UidRange> uids) {
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
    public @Nullable Set<UidRange> getUids() {
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
    public boolean equalsUids(@NonNull NetworkCapabilities nc) {
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
     * mOwnerUid field) always satisfies a NetworkRequest requiring it (of LISTEN
     * or REQUEST types alike), even if the network does not apply to it. That is so a VPN app
     * can see its own network when it listens for it.
     * <p>
     * nc is assumed nonnull. Else, NPE.
     * @see #appliesToUid
     * @hide
     */
    public boolean satisfiedByUids(@NonNull NetworkCapabilities nc) {
        if (null == nc.mUids || null == mUids) return true; // The network satisfies everything.
        for (UidRange requiredRange : mUids) {
            if (requiredRange.contains(nc.mOwnerUid)) return true;
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
    public boolean appliesToUidRange(@Nullable UidRange requiredRange) {
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
    private void combineUids(@NonNull NetworkCapabilities nc) {
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
    public @NonNull NetworkCapabilities setSSID(@Nullable String ssid) {
        mSSID = ssid;
        return this;
    }

    /**
     * Gets the SSID of this network, or null if none or unknown.
     * @hide
     */
    @SystemApi
    @TestApi
    public @Nullable String getSsid() {
        return mSSID;
    }

    /**
     * Tests if the SSID of this network is the same as the SSID of the passed network.
     * @hide
     */
    public boolean equalsSSID(@NonNull NetworkCapabilities nc) {
        return Objects.equals(mSSID, nc.mSSID);
    }

    /**
     * Check if the SSID requirements of this object are matched by the passed object.
     * @hide
     */
    public boolean satisfiedBySSID(@NonNull NetworkCapabilities nc) {
        return mSSID == null || mSSID.equals(nc.mSSID);
    }

    /**
     * Combine SSIDs of the capabilities.
     * <p>
     * This is only legal if either the SSID of this object is null, or both SSIDs are
     * equal.
     * @hide
     */
    private void combineSSIDs(@NonNull NetworkCapabilities nc) {
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
    public void combineCapabilities(@NonNull NetworkCapabilities nc) {
        combineNetCapabilities(nc);
        combineTransportTypes(nc);
        combineLinkBandwidths(nc);
        combineSpecifiers(nc);
        combineTransportInfos(nc);
        combineSignalStrength(nc);
        combineUids(nc);
        combineSSIDs(nc);
        combineRequestor(nc);
        combineAdministratorUids(nc);
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
                && (onlyImmutable || satisfiedBySSID(nc)))
                && (onlyImmutable || satisfiedByRequestor(nc));
    }

    /**
     * Check if our requirements are satisfied by the given {@code NetworkCapabilities}.
     *
     * @param nc the {@code NetworkCapabilities} that may or may not satisfy our requirements.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public boolean satisfiedByNetworkCapabilities(@Nullable NetworkCapabilities nc) {
        return satisfiedByNetworkCapabilities(nc, false);
    }

    /**
     * Check if our immutable requirements are satisfied by the given {@code NetworkCapabilities}.
     *
     * @param nc the {@code NetworkCapabilities} that may or may not satisfy our requirements.
     *
     * @hide
     */
    public boolean satisfiedByImmutableNetworkCapabilities(@Nullable NetworkCapabilities nc) {
        return satisfiedByNetworkCapabilities(nc, true);
    }

    /**
     * Checks that our immutable capabilities are the same as those of the given
     * {@code NetworkCapabilities} and return a String describing any difference.
     * The returned String is empty if there is no difference.
     *
     * @hide
     */
    public String describeImmutableDifferences(@Nullable NetworkCapabilities that) {
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
    public boolean equalRequestableCapabilities(@Nullable NetworkCapabilities nc) {
        if (nc == null) return false;
        return (equalsNetCapabilitiesRequestable(nc) &&
                equalsTransportTypes(nc) &&
                equalsSpecifier(nc));
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null || (obj instanceof NetworkCapabilities == false)) return false;
        NetworkCapabilities that = (NetworkCapabilities) obj;
        return equalsNetCapabilities(that)
                && equalsTransportTypes(that)
                && equalsLinkBandwidths(that)
                && equalsSignalStrength(that)
                && equalsSpecifier(that)
                && equalsTransportInfo(that)
                && equalsUids(that)
                && equalsSSID(that)
                && equalsPrivateDnsBroken(that)
                && equalsRequestor(that)
                && equalsAdministratorUids(that);
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
                + Objects.hashCode(mSSID) * 37
                + Objects.hashCode(mTransportInfo) * 41
                + Objects.hashCode(mPrivateDnsBroken) * 43
                + Objects.hashCode(mRequestorUid) * 47
                + Objects.hashCode(mRequestorPackageName) * 53
                + Arrays.hashCode(mAdministratorUids) * 59;
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
        dest.writeParcelable((Parcelable) mTransportInfo, flags);
        dest.writeInt(mSignalStrength);
        dest.writeArraySet(mUids);
        dest.writeString(mSSID);
        dest.writeBoolean(mPrivateDnsBroken);
        dest.writeIntArray(getAdministratorUids());
        dest.writeInt(mOwnerUid);
        dest.writeInt(mRequestorUid);
        dest.writeString(mRequestorPackageName);
    }

    public static final @android.annotation.NonNull Creator<NetworkCapabilities> CREATOR =
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
                netCap.mTransportInfo = in.readParcelable(null);
                netCap.mSignalStrength = in.readInt();
                netCap.mUids = (ArraySet<UidRange>) in.readArraySet(
                        null /* ClassLoader, null for default */);
                netCap.mSSID = in.readString();
                netCap.mPrivateDnsBroken = in.readBoolean();
                netCap.setAdministratorUids(in.createIntArray());
                netCap.mOwnerUid = in.readInt();
                netCap.mRequestorUid = in.readInt();
                netCap.mRequestorPackageName = in.readString();
                return netCap;
            }
            @Override
            public NetworkCapabilities[] newArray(int size) {
                return new NetworkCapabilities[size];
            }
        };

    @Override
    public @NonNull String toString() {
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
        if (0 != mUnwantedNetworkCapabilities) {
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
        if (mTransportInfo != null) {
            sb.append(" TransportInfo: <").append(mTransportInfo).append(">");
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
        if (mOwnerUid != Process.INVALID_UID) {
            sb.append(" OwnerUid: ").append(mOwnerUid);
        }

        if (mAdministratorUids.length == 0) {
            sb.append(" AdministratorUids: ").append(Arrays.toString(mAdministratorUids));
        }

        if (null != mSSID) {
            sb.append(" SSID: ").append(mSSID);
        }

        if (mPrivateDnsBroken) {
            sb.append(" Private DNS is broken");
        }

        sb.append(" RequestorUid: ").append(mRequestorUid);
        sb.append(" RequestorPackageName: ").append(mRequestorPackageName);

        sb.append("]");
        return sb.toString();
    }


    private interface NameOf {
        String nameOf(int value);
    }

    /**
     * @hide
     */
    public static void appendStringRepresentationOfBitMaskToStringBuilder(@NonNull StringBuilder sb,
            long bitMask, @NonNull NameOf nameFetcher, @NonNull String separator) {
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
    public void dumpDebug(@NonNull ProtoOutputStream proto, long fieldId) {
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
        if (mTransportInfo != null) {
            // TODO b/120653863: write transport-specific info to proto?
        }

        proto.write(NetworkCapabilitiesProto.CAN_REPORT_SIGNAL_STRENGTH, hasSignalStrength());
        proto.write(NetworkCapabilitiesProto.SIGNAL_STRENGTH, mSignalStrength);

        proto.end(token);
    }

    /**
     * @hide
     */
    public static @NonNull String capabilityNamesOf(@Nullable @NetCapability int[] capabilities) {
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
    public static @NonNull String capabilityNameOf(@NetCapability int capability) {
        switch (capability) {
            case NET_CAPABILITY_MMS:                  return "MMS";
            case NET_CAPABILITY_SUPL:                 return "SUPL";
            case NET_CAPABILITY_DUN:                  return "DUN";
            case NET_CAPABILITY_FOTA:                 return "FOTA";
            case NET_CAPABILITY_IMS:                  return "IMS";
            case NET_CAPABILITY_CBS:                  return "CBS";
            case NET_CAPABILITY_WIFI_P2P:             return "WIFI_P2P";
            case NET_CAPABILITY_IA:                   return "IA";
            case NET_CAPABILITY_RCS:                  return "RCS";
            case NET_CAPABILITY_XCAP:                 return "XCAP";
            case NET_CAPABILITY_EIMS:                 return "EIMS";
            case NET_CAPABILITY_NOT_METERED:          return "NOT_METERED";
            case NET_CAPABILITY_INTERNET:             return "INTERNET";
            case NET_CAPABILITY_NOT_RESTRICTED:       return "NOT_RESTRICTED";
            case NET_CAPABILITY_TRUSTED:              return "TRUSTED";
            case NET_CAPABILITY_NOT_VPN:              return "NOT_VPN";
            case NET_CAPABILITY_VALIDATED:            return "VALIDATED";
            case NET_CAPABILITY_CAPTIVE_PORTAL:       return "CAPTIVE_PORTAL";
            case NET_CAPABILITY_NOT_ROAMING:          return "NOT_ROAMING";
            case NET_CAPABILITY_FOREGROUND:           return "FOREGROUND";
            case NET_CAPABILITY_NOT_CONGESTED:        return "NOT_CONGESTED";
            case NET_CAPABILITY_NOT_SUSPENDED:        return "NOT_SUSPENDED";
            case NET_CAPABILITY_OEM_PAID:             return "OEM_PAID";
            case NET_CAPABILITY_MCX:                  return "MCX";
            case NET_CAPABILITY_PARTIAL_CONNECTIVITY: return "PARTIAL_CONNECTIVITY";
            case NET_CAPABILITY_TEMPORARILY_NOT_METERED:    return "TEMPORARILY_NOT_METERED";
            default:                                  return Integer.toString(capability);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static @NonNull String transportNamesOf(@Nullable @Transport int[] types) {
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
    public static @NonNull String transportNameOf(@Transport int transport) {
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

    /**
     * Check if private dns is broken.
     *
     * @return {@code true} if {@code mPrivateDnsBroken} is set when private DNS is broken.
     * @hide
     */
    public boolean isPrivateDnsBroken() {
        return mPrivateDnsBroken;
    }

    /**
     * Set mPrivateDnsBroken to true when private dns is broken.
     *
     * @param broken the status of private DNS to be set.
     * @hide
     */
    public void setPrivateDnsBroken(boolean broken) {
        mPrivateDnsBroken = broken;
    }

    private boolean equalsPrivateDnsBroken(NetworkCapabilities nc) {
        return mPrivateDnsBroken == nc.mPrivateDnsBroken;
    }

    /**
     * Set the UID of the app making the request.
     *
     * For instances of NetworkCapabilities representing a request, sets the
     * UID of the app making the request. For a network created by the system,
     * sets the UID of the only app whose requests can match this network.
     * This can be set to {@link Process#INVALID_UID} if there is no such app,
     * or if this instance of NetworkCapabilities is about to be sent to a
     * party that should not learn about this.
     *
     * @param uid UID of the app.
     * @hide
     */
    public @NonNull NetworkCapabilities setRequestorUid(int uid) {
        mRequestorUid = uid;
        return this;
    }

    /**
     * Returns the UID of the app making the request.
     *
     * For a NetworkRequest being made by an app, contains the app's UID. For a network
     * created by the system, contains the UID of the only app whose requests can match
     * this network, or {@link Process#INVALID_UID} if none or if the
     * caller does not have permission to learn about this.
     *
     * @return the uid of the app making the request.
     * @hide
     */
    public int getRequestorUid() {
        return mRequestorUid;
    }

    /**
     * Set the package name of the app making the request.
     *
     * For instances of NetworkCapabilities representing a request, sets the
     * package name of the app making the request. For a network created by the system,
     * sets the package name of the only app whose requests can match this network.
     * This can be set to null if there is no such app, or if this instance of
     * NetworkCapabilities is about to be sent to a party that should not learn about this.
     *
     * @param packageName package name of the app.
     * @hide
     */
    public @NonNull NetworkCapabilities setRequestorPackageName(@NonNull String packageName) {
        mRequestorPackageName = packageName;
        return this;
    }

    /**
     * Returns the package name of the app making the request.
     *
     * For a NetworkRequest being made by an app, contains the app's package name. For a
     * network created by the system, contains the package name of the only app whose
     * requests can match this network, or null if none or if the caller does not have
     * permission to learn about this.
     *
     * @return the package name of the app making the request.
     * @hide
     */
    @Nullable
    public String getRequestorPackageName() {
        return mRequestorPackageName;
    }

    /**
     * Set the uid and package name of the app causing this network to exist.
     *
     * {@see #setRequestorUid} and {@link #setRequestorPackageName}
     *
     * @param uid UID of the app.
     * @param packageName package name of the app.
     * @hide
     */
    public @NonNull NetworkCapabilities setRequestorUidAndPackageName(
            int uid, @NonNull String packageName) {
        return setRequestorUid(uid).setRequestorPackageName(packageName);
    }

    /**
     * Test whether the passed NetworkCapabilities satisfies the requestor restrictions of this
     * capabilities.
     *
     * This method is called on the NetworkCapabilities embedded in a request with the
     * capabilities of an available network. If the available network, sets a specific
     * requestor (by uid and optionally package name), then this will only match a request from the
     * same app. If either of the capabilities have an unset uid or package name, then it matches
     * everything.
     * <p>
     * nc is assumed nonnull. Else, NPE.
     */
    private boolean satisfiedByRequestor(NetworkCapabilities nc) {
        // No uid set, matches everything.
        if (mRequestorUid == Process.INVALID_UID || nc.mRequestorUid == Process.INVALID_UID) {
            return true;
        }
        // uids don't match.
        if (mRequestorUid != nc.mRequestorUid) return false;
        // No package names set, matches everything
        if (null == nc.mRequestorPackageName || null == mRequestorPackageName) return true;
        // check for package name match.
        return TextUtils.equals(mRequestorPackageName, nc.mRequestorPackageName);
    }

    /**
     * Combine requestor info of the capabilities.
     * <p>
     * This is only legal if either the requestor info of this object is reset, or both info are
     * equal.
     * nc is assumed nonnull.
     */
    private void combineRequestor(@NonNull NetworkCapabilities nc) {
        if (mRequestorUid != Process.INVALID_UID && mRequestorUid != nc.mOwnerUid) {
            throw new IllegalStateException("Can't combine two uids");
        }
        if (mRequestorPackageName != null
                && !mRequestorPackageName.equals(nc.mRequestorPackageName)) {
            throw new IllegalStateException("Can't combine two package names");
        }
        setRequestorUid(nc.mRequestorUid);
        setRequestorPackageName(nc.mRequestorPackageName);
    }

    private boolean equalsRequestor(NetworkCapabilities nc) {
        return mRequestorUid == nc.mRequestorUid
                && TextUtils.equals(mRequestorPackageName, nc.mRequestorPackageName);
    }

    /**
     * Builder class for NetworkCapabilities.
     *
     * This class is mainly for for {@link NetworkAgent} instances to use. Many fields in
     * the built class require holding a signature permission to use - mostly
     * {@link android.Manifest.permission.NETWORK_FACTORY}, but refer to the specific
     * description of each setter. As this class lives entirely in app space it does not
     * enforce these restrictions itself but the system server clears out the relevant
     * fields when receiving a NetworkCapabilities object from a caller without the
     * appropriate permission.
     *
     * Apps don't use this builder directly. Instead, they use {@link NetworkRequest} via
     * its builder object.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final class Builder {
        private final NetworkCapabilities mCaps;

        /**
         * Creates a new Builder to construct NetworkCapabilities objects.
         */
        public Builder() {
            mCaps = new NetworkCapabilities();
        }

        /**
         * Creates a new Builder of NetworkCapabilities from an existing instance.
         */
        public Builder(@NonNull final NetworkCapabilities nc) {
            Objects.requireNonNull(nc);
            mCaps = new NetworkCapabilities(nc);
        }

        /**
         * Adds the given transport type.
         *
         * Multiple transports may be added. Note that when searching for a network to satisfy a
         * request, satisfying any of the transports listed in the request will satisfy the request.
         * For example {@code TRANSPORT_WIFI} and {@code TRANSPORT_ETHERNET} added to a
         * {@code NetworkCapabilities} would cause either a Wi-Fi network or an Ethernet network
         * to be selected. This is logically different than
         * {@code NetworkCapabilities.NET_CAPABILITY_*}.
         *
         * @param transportType the transport type to be added or removed.
         * @return this builder
         */
        @NonNull
        public Builder addTransportType(@Transport int transportType) {
            checkValidTransportType(transportType);
            mCaps.addTransportType(transportType);
            return this;
        }

        /**
         * Removes the given transport type.
         *
         * {@see #addTransportType}.
         *
         * @param transportType the transport type to be added or removed.
         * @return this builder
         */
        @NonNull
        public Builder removeTransportType(@Transport int transportType) {
            checkValidTransportType(transportType);
            mCaps.removeTransportType(transportType);
            return this;
        }

        /**
         * Adds the given capability.
         *
         * @param capability the capability
         * @return this builder
         */
        @NonNull
        public Builder addCapability(@NetCapability final int capability) {
            mCaps.setCapability(capability, true);
            return this;
        }

        /**
         * Removes the given capability.
         *
         * @param capability the capability
         * @return this builder
         */
        @NonNull
        public Builder removeCapability(@NetCapability final int capability) {
            mCaps.setCapability(capability, false);
            return this;
        }

        /**
         * Sets the owner UID.
         *
         * The default value is {@link Process#INVALID_UID}. Pass this value to reset.
         *
         * Note: for security the system will clear out this field when received from a
         * non-privileged source.
         *
         * @param ownerUid the owner UID
         * @return this builder
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
        public Builder setOwnerUid(final int ownerUid) {
            mCaps.setOwnerUid(ownerUid);
            return this;
        }

        /**
         * Sets the list of UIDs that are administrators of this network.
         *
         * <p>UIDs included in administratorUids gain administrator privileges over this
         * Network. Examples of UIDs that should be included in administratorUids are:
         * <ul>
         *     <li>Carrier apps with privileges for the relevant subscription
         *     <li>Active VPN apps
         *     <li>Other application groups with a particular Network-related role
         * </ul>
         *
         * <p>In general, user-supplied networks (such as WiFi networks) do not have
         * administrators.
         *
         * <p>An app is granted owner privileges over Networks that it supplies. The owner
         * UID MUST always be included in administratorUids.
         *
         * The default value is the empty array. Pass an empty array to reset.
         *
         * Note: for security the system will clear out this field when received from a
         * non-privileged source, such as an app using reflection to call this or
         * mutate the member in the built object.
         *
         * @param administratorUids the UIDs to be set as administrators of this Network.
         * @return this builder
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
        public Builder setAdministratorUids(@NonNull final int[] administratorUids) {
            Objects.requireNonNull(administratorUids);
            mCaps.setAdministratorUids(administratorUids);
            return this;
        }

        /**
         * Sets the upstream bandwidth of the link.
         *
         * Sets the upstream bandwidth for this network in Kbps. This always only refers to
         * the estimated first hop transport bandwidth.
         * <p>
         * Note that when used to request a network, this specifies the minimum acceptable.
         * When received as the state of an existing network this specifies the typical
         * first hop bandwidth expected. This is never measured, but rather is inferred
         * from technology type and other link parameters. It could be used to differentiate
         * between very slow 1xRTT cellular links and other faster networks or even between
         * 802.11b vs 802.11AC wifi technologies. It should not be used to differentiate between
         * fast backhauls and slow backhauls.
         *
         * @param upKbps the estimated first hop upstream (device to network) bandwidth.
         * @return this builder
         */
        @NonNull
        public Builder setLinkUpstreamBandwidthKbps(final int upKbps) {
            mCaps.setLinkUpstreamBandwidthKbps(upKbps);
            return this;
        }

        /**
         * Sets the downstream bandwidth for this network in Kbps. This always only refers to
         * the estimated first hop transport bandwidth.
         * <p>
         * Note that when used to request a network, this specifies the minimum acceptable.
         * When received as the state of an existing network this specifies the typical
         * first hop bandwidth expected. This is never measured, but rather is inferred
         * from technology type and other link parameters. It could be used to differentiate
         * between very slow 1xRTT cellular links and other faster networks or even between
         * 802.11b vs 802.11AC wifi technologies. It should not be used to differentiate between
         * fast backhauls and slow backhauls.
         *
         * @param downKbps the estimated first hop downstream (network to device) bandwidth.
         * @return this builder
         */
        @NonNull
        public Builder setLinkDownstreamBandwidthKbps(final int downKbps) {
            mCaps.setLinkDownstreamBandwidthKbps(downKbps);
            return this;
        }

        /**
         * Sets the optional bearer specific network specifier.
         * This has no meaning if a single transport is also not specified, so calling
         * this without a single transport set will generate an exception, as will
         * subsequently adding or removing transports after this is set.
         * </p>
         *
         * @param specifier a concrete, parcelable framework class that extends NetworkSpecifier,
         *        or null to clear it.
         * @return this builder
         */
        @NonNull
        public Builder setNetworkSpecifier(@Nullable final NetworkSpecifier specifier) {
            mCaps.setNetworkSpecifier(specifier);
            return this;
        }

        /**
         * Sets the optional transport specific information.
         *
         * @param info A concrete, parcelable framework class that extends {@link TransportInfo},
         *             or null to clear it.
         * @return this builder
         */
        @NonNull
        public Builder setTransportInfo(@Nullable final TransportInfo info) {
            mCaps.setTransportInfo(info);
            return this;
        }

        /**
         * Sets the signal strength. This is a signed integer, with higher values indicating a
         * stronger signal. The exact units are bearer-dependent. For example, Wi-Fi uses the
         * same RSSI units reported by wifi code.
         * <p>
         * Note that when used to register a network callback, this specifies the minimum
         * acceptable signal strength. When received as the state of an existing network it
         * specifies the current value. A value of code SIGNAL_STRENGTH_UNSPECIFIED} means
         * no value when received and has no effect when requesting a callback.
         *
         * Note: for security the system will throw if it receives a NetworkRequest where
         * the underlying NetworkCapabilities has this member set from a source that does
         * not hold the {@link android.Manifest.permission.NETWORK_SIGNAL_STRENGTH_WAKEUP}
         * permission. Apps with this permission can use this indirectly through
         * {@link android.net.NetworkRequest}.
         *
         * @param signalStrength the bearer-specific signal strength.
         * @return this builder
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.NETWORK_SIGNAL_STRENGTH_WAKEUP)
        public Builder setSignalStrength(final int signalStrength) {
            mCaps.setSignalStrength(signalStrength);
            return this;
        }

        /**
         * Sets the SSID of this network.
         *
         * Note: for security the system will clear out this field when received from a
         * non-privileged source, like an app using reflection to set this.
         *
         * @param ssid the SSID, or null to clear it.
         * @return this builder
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
        public Builder setSsid(@Nullable final String ssid) {
            mCaps.setSSID(ssid);
            return this;
        }

        /**
         * Set the uid of the app causing this network to exist.
         *
         * Note: for security the system will clear out this field when received from a
         * non-privileged source.
         *
         * @param uid UID of the app.
         * @return this builder
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
        public Builder setRequestorUid(final int uid) {
            mCaps.setRequestorUid(uid);
            return this;
        }

        /**
         * Set the package name of the app causing this network to exist.
         *
         * Note: for security the system will clear out this field when received from a
         * non-privileged source.
         *
         * @param packageName package name of the app, or null to clear it.
         * @return this builder
         */
        @NonNull
        @RequiresPermission(android.Manifest.permission.NETWORK_FACTORY)
        public Builder setRequestorPackageName(@Nullable final String packageName) {
            mCaps.setRequestorPackageName(packageName);
            return this;
        }

        /**
         * Builds the instance of the capabilities.
         *
         * @return the built instance of NetworkCapabilities.
         */
        @NonNull
        public NetworkCapabilities build() {
            if (mCaps.getOwnerUid() != Process.INVALID_UID) {
                if (!ArrayUtils.contains(mCaps.getAdministratorUids(), mCaps.getOwnerUid())) {
                    throw new IllegalStateException("The owner UID must be included in "
                            + " administrator UIDs.");
                }
            }
            return new NetworkCapabilities(mCaps);
        }
    }
}
