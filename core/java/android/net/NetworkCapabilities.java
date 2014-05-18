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
import android.util.Log;

import java.lang.IllegalArgumentException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This class represents the capabilities of a network.  This is used both to specify
 * needs to {@link ConnectivityManager} and when inspecting a network.
 *
 * Note that this replaces the old {@link ConnectivityManager#TYPE_MOBILE} method
 * of network selection.  Rather than indicate a need for Wi-Fi because an application
 * needs high bandwidth and risk obselence when a new, fast network appears (like LTE),
 * the application should specify it needs high bandwidth.  Similarly if an application
 * needs an unmetered network for a bulk transfer it can specify that rather than assuming
 * all cellular based connections are metered and all Wi-Fi based connections are not.
 */
public final class NetworkCapabilities implements Parcelable {
    private static final String TAG = "NetworkCapabilities";
    private static final boolean DBG = false;

    public NetworkCapabilities() {
    }

    public NetworkCapabilities(NetworkCapabilities nc) {
        if (nc != null) {
            mNetworkCapabilities = nc.mNetworkCapabilities;
            mTransportTypes = nc.mTransportTypes;
            mLinkUpBandwidthKbps = nc.mLinkUpBandwidthKbps;
            mLinkDownBandwidthKbps = nc.mLinkDownBandwidthKbps;
        }
    }

    /**
     * Represents the network's capabilities.  If any are specified they will be satisfied
     * by any Network that matches all of them.
     */
    private long mNetworkCapabilities = (1 << NET_CAPABILITY_NOT_RESTRICTED);

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
     * Emergency IMS servers, used for network signaling during emergency calls.
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

    private static final int MIN_NET_CAPABILITY = NET_CAPABILITY_MMS;
    private static final int MAX_NET_CAPABILITY = NET_CAPABILITY_NOT_RESTRICTED;

    /**
     * Adds the given capability to this {@code NetworkCapability} instance.
     * Multiple capabilities may be applied sequentially.  Note that when searching
     * for a network to satisfy a request, all capabilities requested must be satisfied.
     *
     * @param networkCapability the {@code NetworkCapabilities.NET_CAPABILITY_*} to be added.
     */
    public void addNetworkCapability(int networkCapability) {
        if (networkCapability < MIN_NET_CAPABILITY ||
                networkCapability > MAX_NET_CAPABILITY) {
            throw new IllegalArgumentException("NetworkCapability out of range");
        }
        mNetworkCapabilities |= 1 << networkCapability;
    }

    /**
     * Removes (if found) the given capability from this {@code NetworkCapability} instance.
     *
     * @param networkCapability the {@code NetworkCapabilities.NET_CAPABILTIY_*} to be removed.
     */
    public void removeNetworkCapability(int networkCapability) {
        if (networkCapability < MIN_NET_CAPABILITY ||
                networkCapability > MAX_NET_CAPABILITY) {
            throw new IllegalArgumentException("NetworkCapability out of range");
        }
        mNetworkCapabilities &= ~(1 << networkCapability);
    }

    /**
     * Gets all the capabilities set on this {@code NetworkCapability} instance.
     *
     * @return a {@link Collection} of {@code NetworkCapabilities.NET_CAPABILITY_*} values
     *         for this instance.
     */
    public Collection<Integer> getNetworkCapabilities() {
        return enumerateBits(mNetworkCapabilities);
    }

    /**
     * Tests for the presence of a capabilitity on this instance.
     *
     * @param networkCapability the {@code NetworkCapabilities.NET_CAPABILITY_*} to be tested for.
     * @return {@code true} if set on this instance.
     */
    public boolean hasCapability(int networkCapability) {
        if (networkCapability < MIN_NET_CAPABILITY ||
                networkCapability > MAX_NET_CAPABILITY) {
            return false;
        }
        return ((mNetworkCapabilities & (1 << networkCapability)) != 0);
    }

    private Collection<Integer> enumerateBits(long val) {
        ArrayList<Integer> result = new ArrayList<Integer>();
        int resource = 0;
        while (val > 0) {
            if ((val & 1) == 1) result.add(resource);
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

    private boolean equalsNetCapabilities(NetworkCapabilities nc) {
        return (nc.mNetworkCapabilities == this.mNetworkCapabilities);
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

    private static final int MIN_TRANSPORT = TRANSPORT_CELLULAR;
    private static final int MAX_TRANSPORT = TRANSPORT_ETHERNET;

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
     */
    public void addTransportType(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            throw new IllegalArgumentException("TransportType out of range");
        }
        mTransportTypes |= 1 << transportType;
    }

    /**
     * Removes (if found) the given transport from this {@code NetworkCapability} instance.
     *
     * @param transportType the {@code NetworkCapabilities.TRANSPORT_*} to be removed.
     */
    public void removeTransportType(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            throw new IllegalArgumentException("TransportType out of range");
        }
        mTransportTypes &= ~(1 << transportType);
    }

    /**
     * Gets all the transports set on this {@code NetworkCapability} instance.
     *
     * @return a {@link Collection} of {@code NetworkCapabilities.TRANSPORT_*} values
     *         for this instance.
     */
    public Collection<Integer> getTransportTypes() {
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
    private boolean equalsTransportTypes(NetworkCapabilities nc) {
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

    /**
     * Combine a set of Capabilities to this one.  Useful for coming up with the complete set
     * {@hide}
     */
    public void combineCapabilities(NetworkCapabilities nc) {
        combineNetCapabilities(nc);
        combineTransportTypes(nc);
        combineLinkBandwidths(nc);
    }

    /**
     * Check if our requirements are satisfied by the given Capabilities.
     * {@hide}
     */
    public boolean satisfiedByNetworkCapabilities(NetworkCapabilities nc) {
        return (nc != null &&
                satisfiedByNetCapabilities(nc) &&
                satisfiedByTransportTypes(nc) &&
                satisfiedByLinkBandwidths(nc));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || (obj instanceof NetworkCapabilities == false)) return false;
        NetworkCapabilities that = (NetworkCapabilities)obj;
        return (equalsNetCapabilities(that) &&
                equalsTransportTypes(that) &&
                equalsLinkBandwidths(that));
    }

    @Override
    public int hashCode() {
        return ((int)(mNetworkCapabilities & 0xFFFFFFFF) +
                ((int)(mNetworkCapabilities >> 32) * 3) +
                ((int)(mTransportTypes & 0xFFFFFFFF) * 5) +
                ((int)(mTransportTypes >> 32) * 7) +
                (mLinkUpBandwidthKbps * 11) +
                (mLinkDownBandwidthKbps * 13));
    }

    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mNetworkCapabilities);
        dest.writeLong(mTransportTypes);
        dest.writeInt(mLinkUpBandwidthKbps);
        dest.writeInt(mLinkDownBandwidthKbps);
    }
    public static final Creator<NetworkCapabilities> CREATOR =
        new Creator<NetworkCapabilities>() {
            public NetworkCapabilities createFromParcel(Parcel in) {
                NetworkCapabilities netCap = new NetworkCapabilities();

                netCap.mNetworkCapabilities = in.readLong();
                netCap.mTransportTypes = in.readLong();
                netCap.mLinkUpBandwidthKbps = in.readInt();
                netCap.mLinkDownBandwidthKbps = in.readInt();
                return netCap;
            }
            public NetworkCapabilities[] newArray(int size) {
                return new NetworkCapabilities[size];
            }
        };

    public String toString() {
        Collection<Integer> types = getTransportTypes();
        String transports = (types.size() > 0 ? " Transports: " : "");
        Iterator<Integer> i = types.iterator();
        while (i.hasNext()) {
            switch (i.next()) {
                case TRANSPORT_CELLULAR:    transports += "CELLULAR"; break;
                case TRANSPORT_WIFI:        transports += "WIFI"; break;
                case TRANSPORT_BLUETOOTH:   transports += "BLUETOOTH"; break;
                case TRANSPORT_ETHERNET:    transports += "ETHERNET"; break;
            }
            if (i.hasNext()) transports += "|";
        }

        types = getNetworkCapabilities();
        String capabilities = (types.size() > 0 ? " Capabilities: " : "");
        i = types.iterator();
        while (i.hasNext()) {
            switch (i.next().intValue()) {
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
            }
            if (i.hasNext()) capabilities += "&";
        }

        String upBand = ((mLinkUpBandwidthKbps > 0) ? " LinkUpBandwidth>=" +
                mLinkUpBandwidthKbps + "Kbps" : "");
        String dnBand = ((mLinkDownBandwidthKbps > 0) ? " LinkDnBandwidth>=" +
                mLinkDownBandwidthKbps + "Kbps" : "");

        return "[" + transports + capabilities + upBand + dnBand + "]";
    }
}
