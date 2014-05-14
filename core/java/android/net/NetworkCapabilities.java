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
 * A class representing the capabilities of a network
 * @hide
 */
public final class NetworkCapabilities implements Parcelable {
    private static final String TAG = "NetworkCapabilities";
    private static final boolean DBG = false;


    /**
     * Represents the network's capabilities.  If any are specified they will be satisfied
     * by any Network that matches all of them.
     */
    private long mNetworkCapabilities = (1 << NET_CAPABILITY_NOT_RESTRICTED);

    /**
     * Values for NetworkCapabilities.  Roughly matches/extends deprecated
     * ConnectivityManager TYPE_*
     */
    public static final int NET_CAPABILITY_MMS            = 0;
    public static final int NET_CAPABILITY_SUPL           = 1;
    public static final int NET_CAPABILITY_DUN            = 2;
    public static final int NET_CAPABILITY_FOTA           = 3;
    public static final int NET_CAPABILITY_IMS            = 4;
    public static final int NET_CAPABILITY_CBS            = 5;
    public static final int NET_CAPABILITY_WIFI_P2P       = 6;
    public static final int NET_CAPABILITY_IA             = 7;
    public static final int NET_CAPABILITY_RCS            = 8;
    public static final int NET_CAPABILITY_XCAP           = 9;
    public static final int NET_CAPABILITY_EIMS           = 10;
    public static final int NET_CAPABILITY_NOT_METERED    = 11;
    public static final int NET_CAPABILITY_INTERNET       = 12;
    /** Set by default */
    public static final int NET_CAPABILITY_NOT_RESTRICTED = 13;

    private static final int MIN_NET_CAPABILITY = NET_CAPABILITY_MMS;
    private static final int MAX_NET_CAPABILITY = NET_CAPABILITY_NOT_RESTRICTED;

    public void addNetworkCapability(int networkCapability) {
        if (networkCapability < MIN_NET_CAPABILITY ||
                networkCapability > MAX_NET_CAPABILITY) {
            throw new IllegalArgumentException("NetworkCapability out of range");
        }
        mNetworkCapabilities |= 1 << networkCapability;
    }
    public void removeNetworkCapability(int networkCapability) {
        if (networkCapability < MIN_NET_CAPABILITY ||
                networkCapability > MAX_NET_CAPABILITY) {
            throw new IllegalArgumentException("NetworkCapability out of range");
        }
        mNetworkCapabilities &= ~(1 << networkCapability);
    }
    public Collection<Integer> getNetworkCapabilities() {
        return enumerateBits(mNetworkCapabilities);
    }
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
     * Values for TransportType
     */
    public static final int TRANSPORT_CELLULAR = 0;
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_BLUETOOTH = 2;
    public static final int TRANSPORT_ETHERNET = 3;

    private static final int MIN_TRANSPORT = TRANSPORT_CELLULAR;
    private static final int MAX_TRANSPORT = TRANSPORT_ETHERNET;

    public void addTransportType(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            throw new IllegalArgumentException("TransportType out of range");
        }
        mTransportTypes |= 1 << transportType;
    }
    public void removeTransportType(int transportType) {
        if (transportType < MIN_TRANSPORT || transportType > MAX_TRANSPORT) {
            throw new IllegalArgumentException("TransportType out of range");
        }
        mTransportTypes &= ~(1 << transportType);
    }
    public Collection<Integer> getTransportTypes() {
        return enumerateBits(mTransportTypes);
    }
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

    public void setLinkUpstreamBandwidthKbps(int upKbps) {
        mLinkUpBandwidthKbps = upKbps;
    }
    public int getLinkUpstreamBandwidthKbps() {
        return mLinkUpBandwidthKbps;
    }
    public void setLinkDownstreamBandwidthKbps(int downKbps) {
        mLinkDownBandwidthKbps = downKbps;
    }
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

    // Parcelable
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
