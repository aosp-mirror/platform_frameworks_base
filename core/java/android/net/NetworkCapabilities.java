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
import android.text.TextUtils;
import java.lang.IllegalArgumentException;

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

    private static final int MIN_NET_CAPABILITY = NET_CAPABILITY_MMS;
    private static final int MAX_NET_CAPABILITY = NET_CAPABILITY_CAPTIVE_PORTAL;

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
    private static final long RESTRICTED_CAPABILITIES =
            (1 << NET_CAPABILITY_CBS) |
            (1 << NET_CAPABILITY_DUN) |
            (1 << NET_CAPABILITY_EIMS) |
            (1 << NET_CAPABILITY_FOTA) |
            (1 << NET_CAPABILITY_IA) |
            (1 << NET_CAPABILITY_IMS) |
            (1 << NET_CAPABILITY_RCS) |
            (1 << NET_CAPABILITY_XCAP);

    /**
     * Adds the given capability to this {@code NetworkCapability} instance.
     * Multiple capabilities may be applied sequentially.  Note that when searching
     * for a network to satisfy a request, all capabilities requested must be satisfied.
     *
     * @param capability the {@code NetworkCapabilities.NET_CAPABILITY_*} to be added.
     * @return This NetworkCapability to facilitate chaining.
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
     * @return This NetworkCapability to facilitate chaining.
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
        return enumerateBits(mNetworkCapabilities);
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

    private int[] enumerateBits(long val) {
        int size = Long.bitCount(val);
        int[] result = new int[size];
        int index = 0;
        int resource = 0;
        while (val > 0) {
            if ((val & 1) == 1) result[index++] = resource;
            val = val >> 1;
            resource++;
        }
        return result;
    }

    private void combineNetCapabilities(NetworkCapabilities nc) {
        this.mNetworkCapabilities |= nc.mNetworkCapabilities;
    }

    private boolean satisfiedByNetCapabilities(NetworkCapabilities nc) {
        return ((nc.mNetworkCapabilities & this.mNetworkCapabilities) == this.mNetworkCapabilities);
    }

    /** @hide */
    public boolean equalsNetCapabilities(NetworkCapabilities nc) {
        return (nc.mNetworkCapabilities == this.mNetworkCapabilities);
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
        // If all the capabilities are typically provided by restricted networks, conclude that this
        // network is restricted.
        if ((mNetworkCapabilities & ~(DEFAULT_CAPABILITIES | RESTRICTED_CAPABILITIES)) == 0)
            removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
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

    private static final int MIN_TRANSPORT = TRANSPORT_CELLULAR;
    private static final int MAX_TRANSPORT = TRANSPORT_VPN;

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
     * @return This NetworkCapability to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities addTransportType(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            throw new IllegalArgumentException("TransportType out of range");
        }
        mTransportTypes |= 1 << transportType;
        setNetworkSpecifier(mNetworkSpecifier); // used for exception checking
        return this;
    }

    /**
     * Removes (if found) the given transport from this {@code NetworkCapability} instance.
     *
     * @param transportType the {@code NetworkCapabilities.TRANSPORT_*} to be removed.
     * @return This NetworkCapability to facilitate chaining.
     * @hide
     */
    public NetworkCapabilities removeTransportType(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            throw new IllegalArgumentException("TransportType out of range");
        }
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
        return enumerateBits(mTransportTypes);
    }

    /**
     * Tests for the presence of a transport on this instance.
     *
     * @param transportType the {@code NetworkCapabilities.TRANSPORT_*} to be tested for.
     * @return {@code true} if set on this instance.
     */
    public boolean hasTransport(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            return false;
        }
        return ((mTransportTypes & (1 << transportType)) != 0);
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

    private String mNetworkSpecifier;
    /**
     * Sets the optional bearer specific network specifier.
     * This has no meaning if a single transport is also not specified, so calling
     * this without a single transport set will generate an exception, as will
     * subsequently adding or removing transports after this is set.
     * </p>
     * The interpretation of this {@code String} is bearer specific and bearers that use
     * it should document their particulars.  For example, Bluetooth may use some sort of
     * device id while WiFi could used SSID and/or BSSID.  Cellular may use carrier SPN (name)
     * or Subscription ID.
     *
     * @param networkSpecifier An {@code String} of opaque format used to specify the bearer
     *                         specific network specifier where the bearer has a choice of
     *                         networks.
     * @hide
     */
    public void setNetworkSpecifier(String networkSpecifier) {
        if (TextUtils.isEmpty(networkSpecifier) == false && Long.bitCount(mTransportTypes) != 1) {
            throw new IllegalStateException("Must have a single transport specified to use " +
                    "setNetworkSpecifier");
        }
        mNetworkSpecifier = networkSpecifier;
    }

    /**
     * Gets the optional bearer specific network specifier.
     *
     * @return The optional {@code String} specifying the bearer specific network specifier.
     *         See {@link #setNetworkSpecifier}.
     * @hide
     */
    public String getNetworkSpecifier() {
        return mNetworkSpecifier;
    }

    private void combineSpecifiers(NetworkCapabilities nc) {
        String otherSpecifier = nc.getNetworkSpecifier();
        if (TextUtils.isEmpty(otherSpecifier)) return;
        if (TextUtils.isEmpty(mNetworkSpecifier) == false) {
            throw new IllegalStateException("Can't combine two networkSpecifiers");
        }
        setNetworkSpecifier(otherSpecifier);
    }
    private boolean satisfiedBySpecifier(NetworkCapabilities nc) {
        return (TextUtils.isEmpty(mNetworkSpecifier) ||
                mNetworkSpecifier.equals(nc.mNetworkSpecifier));
    }
    private boolean equalsSpecifier(NetworkCapabilities nc) {
        if (TextUtils.isEmpty(mNetworkSpecifier)) {
            return TextUtils.isEmpty(nc.mNetworkSpecifier);
        } else {
            return mNetworkSpecifier.equals(nc.mNetworkSpecifier);
        }
    }

    /**
     * Combine a set of Capabilities to this one.  Useful for coming up with the complete set
     * {@hide}
     */
    public void combineCapabilities(NetworkCapabilities nc) {
        combineNetCapabilities(nc);
        combineTransportTypes(nc);
        combineLinkBandwidths(nc);
        combineSpecifiers(nc);
    }

    /**
     * Check if our requirements are satisfied by the given Capabilities.
     * {@hide}
     */
    public boolean satisfiedByNetworkCapabilities(NetworkCapabilities nc) {
        return (nc != null &&
                satisfiedByNetCapabilities(nc) &&
                satisfiedByTransportTypes(nc) &&
                satisfiedByLinkBandwidths(nc) &&
                satisfiedBySpecifier(nc));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || (obj instanceof NetworkCapabilities == false)) return false;
        NetworkCapabilities that = (NetworkCapabilities)obj;
        return (equalsNetCapabilities(that) &&
                equalsTransportTypes(that) &&
                equalsLinkBandwidths(that) &&
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
                (TextUtils.isEmpty(mNetworkSpecifier) ? 0 : mNetworkSpecifier.hashCode() * 17));
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
        dest.writeString(mNetworkSpecifier);
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
                netCap.mNetworkSpecifier = in.readString();
                return netCap;
            }
            @Override
            public NetworkCapabilities[] newArray(int size) {
                return new NetworkCapabilities[size];
            }
        };

    @Override
    public String toString() {
        int[] types = getTransportTypes();
        String transports = (types.length > 0 ? " Transports: " : "");
        for (int i = 0; i < types.length;) {
            switch (types[i]) {
                case TRANSPORT_CELLULAR:    transports += "CELLULAR"; break;
                case TRANSPORT_WIFI:        transports += "WIFI"; break;
                case TRANSPORT_BLUETOOTH:   transports += "BLUETOOTH"; break;
                case TRANSPORT_ETHERNET:    transports += "ETHERNET"; break;
                case TRANSPORT_VPN:         transports += "VPN"; break;
            }
            if (++i < types.length) transports += "|";
        }

        types = getCapabilities();
        String capabilities = (types.length > 0 ? " Capabilities: " : "");
        for (int i = 0; i < types.length; ) {
            switch (types[i]) {
                case NET_CAPABILITY_MMS:            capabilities += "MMS"; break;
                case NET_CAPABILITY_SUPL:           capabilities += "SUPL"; break;
                case NET_CAPABILITY_DUN:            capabilities += "DUN"; break;
                case NET_CAPABILITY_FOTA:           capabilities += "FOTA"; break;
                case NET_CAPABILITY_IMS:            capabilities += "IMS"; break;
                case NET_CAPABILITY_CBS:            capabilities += "CBS"; break;
                case NET_CAPABILITY_WIFI_P2P:       capabilities += "WIFI_P2P"; break;
                case NET_CAPABILITY_IA:             capabilities += "IA"; break;
                case NET_CAPABILITY_RCS:            capabilities += "RCS"; break;
                case NET_CAPABILITY_XCAP:           capabilities += "XCAP"; break;
                case NET_CAPABILITY_EIMS:           capabilities += "EIMS"; break;
                case NET_CAPABILITY_NOT_METERED:    capabilities += "NOT_METERED"; break;
                case NET_CAPABILITY_INTERNET:       capabilities += "INTERNET"; break;
                case NET_CAPABILITY_NOT_RESTRICTED: capabilities += "NOT_RESTRICTED"; break;
                case NET_CAPABILITY_TRUSTED:        capabilities += "TRUSTED"; break;
                case NET_CAPABILITY_NOT_VPN:        capabilities += "NOT_VPN"; break;
                case NET_CAPABILITY_VALIDATED:      capabilities += "VALIDATED"; break;
                case NET_CAPABILITY_CAPTIVE_PORTAL: capabilities += "CAPTIVE_PORTAL"; break;
            }
            if (++i < types.length) capabilities += "&";
        }

        String upBand = ((mLinkUpBandwidthKbps > 0) ? " LinkUpBandwidth>=" +
                mLinkUpBandwidthKbps + "Kbps" : "");
        String dnBand = ((mLinkDownBandwidthKbps > 0) ? " LinkDnBandwidth>=" +
                mLinkDownBandwidthKbps + "Kbps" : "");

        String specifier = (mNetworkSpecifier == null ?
                "" : " Specifier: <" + mNetworkSpecifier + ">");

        return "[" + transports + capabilities + upBand + dnBand + specifier + "]";
    }
}
