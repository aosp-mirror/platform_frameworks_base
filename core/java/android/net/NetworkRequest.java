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

/**
 * Defines a request for a network, made through {@link NetworkRequest.Builder} and used
 * to request a network via {@link ConnectivityManager#requestNetwork} or listen for changes
 * via {@link ConnectivityManager#registerNetworkCallback}.
 */
public class NetworkRequest implements Parcelable {
    /**
     * The {@link NetworkCapabilities} that define this request.
     * @hide
     */
    public final NetworkCapabilities networkCapabilities;

    /**
     * Identifies the request.  NetworkRequests should only be constructed by
     * the Framework and given out to applications as tokens to be used to identify
     * the request.
     * @hide
     */
    public final int requestId;

    /**
     * Set for legacy requests and the default.  Set to TYPE_NONE for none.
     * Causes CONNECTIVITY_ACTION broadcasts to be sent.
     * @hide
     */
    public final int legacyType;

    /**
     * @hide
     */
    public NetworkRequest(NetworkCapabilities nc, int legacyType, int rId) {
        if (nc == null) {
            throw new NullPointerException();
        }
        requestId = rId;
        networkCapabilities = nc;
        this.legacyType = legacyType;
    }

    /**
     * @hide
     */
    public NetworkRequest(NetworkRequest that) {
        networkCapabilities = new NetworkCapabilities(that.networkCapabilities);
        requestId = that.requestId;
        this.legacyType = that.legacyType;
    }

    /**
     * Builder used to create {@link NetworkRequest} objects.  Specify the Network features
     * needed in terms of {@link NetworkCapabilities} features
     */
    public static class Builder {
        private final NetworkCapabilities mNetworkCapabilities = new NetworkCapabilities();

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Build {@link NetworkRequest} give the current set of capabilities.
         */
        public NetworkRequest build() {
            // Make a copy of mNetworkCapabilities so we don't inadvertently remove NOT_RESTRICTED
            // when later an unrestricted capability could be added to mNetworkCapabilities, in
            // which case NOT_RESTRICTED should be returned to mNetworkCapabilities, which
            // maybeMarkCapabilitiesRestricted() doesn't add back.
            final NetworkCapabilities nc = new NetworkCapabilities(mNetworkCapabilities);
            nc.maybeMarkCapabilitiesRestricted();
            return new NetworkRequest(nc, ConnectivityManager.TYPE_NONE,
                    ConnectivityManager.REQUEST_ID_UNSET);
        }

        /**
         * Add the given capability requirement to this builder.  These represent
         * the requested network's required capabilities.  Note that when searching
         * for a network to satisfy a request, all capabilities requested must be
         * satisfied.  See {@link NetworkCapabilities} for {@code NET_CAPABILITIY_*}
         * definitions.
         *
         * @param capability The {@code NetworkCapabilities.NET_CAPABILITY_*} to add.
         * @return The builder to facilitate chaining
         *         {@code builder.addCapability(...).addCapability();}.
         */
        public Builder addCapability(int capability) {
            mNetworkCapabilities.addCapability(capability);
            return this;
        }

        /**
         * Removes (if found) the given capability from this builder instance.
         *
         * @param capability The {@code NetworkCapabilities.NET_CAPABILITY_*} to remove.
         * @return The builder to facilitate chaining.
         */
        public Builder removeCapability(int capability) {
            mNetworkCapabilities.removeCapability(capability);
            return this;
        }

        /**
         * Completely clears all the {@code NetworkCapabilities} from this builder instance,
         * removing even the capabilities that are set by default when the object is constructed.
         *
         * @return The builder to facilitate chaining.
         * @hide
         */
        public Builder clearCapabilities() {
            mNetworkCapabilities.clearAll();
            return this;
        }

        /**
         * Adds the given transport requirement to this builder.  These represent
         * the set of allowed transports for the request.  Only networks using one
         * of these transports will satisfy the request.  If no particular transports
         * are required, none should be specified here.  See {@link NetworkCapabilities}
         * for {@code TRANSPORT_*} definitions.
         *
         * @param transportType The {@code NetworkCapabilities.TRANSPORT_*} to add.
         * @return The builder to facilitate chaining.
         */
        public Builder addTransportType(int transportType) {
            mNetworkCapabilities.addTransportType(transportType);
            return this;
        }

        /**
         * Removes (if found) the given transport from this builder instance.
         *
         * @param transportType The {@code NetworkCapabilities.TRANSPORT_*} to remove.
         * @return The builder to facilitate chaining.
         */
        public Builder removeTransportType(int transportType) {
            mNetworkCapabilities.removeTransportType(transportType);
            return this;
        }

        /**
         * @hide
         */
        public Builder setLinkUpstreamBandwidthKbps(int upKbps) {
            mNetworkCapabilities.setLinkUpstreamBandwidthKbps(upKbps);
            return this;
        }
        /**
         * @hide
         */
        public Builder setLinkDownstreamBandwidthKbps(int downKbps) {
            mNetworkCapabilities.setLinkDownstreamBandwidthKbps(downKbps);
            return this;
        }

        /**
         * Sets the optional bearer specific network specifier.
         * This has no meaning if a single transport is also not specified, so calling
         * this without a single transport set will generate an exception, as will
         * subsequently adding or removing transports after this is set.
         * </p>
         * The interpretation of this {@code String} is bearer specific and bearers that use
         * it should document their particulars.  For example, Bluetooth may use some sort of
         * device id while WiFi could used ssid and/or bssid.  Cellular may use carrier spn.
         *
         * @param networkSpecifier An {@code String} of opaque format used to specify the bearer
         *                         specific network specifier where the bearer has a choice of
         *                         networks.
         */
        public Builder setNetworkSpecifier(String networkSpecifier) {
            if (NetworkCapabilities.MATCH_ALL_REQUESTS_NETWORK_SPECIFIER.equals(networkSpecifier)) {
                throw new IllegalArgumentException("Invalid network specifier - must not be '"
                        + NetworkCapabilities.MATCH_ALL_REQUESTS_NETWORK_SPECIFIER + "'");
            }
            mNetworkCapabilities.setNetworkSpecifier(networkSpecifier);
            return this;
        }

        /**
         * Sets the signal strength. This is a signed integer, with higher values indicating a
         * stronger signal. The exact units are bearer-dependent. For example, Wi-Fi uses the same
         * RSSI units reported by WifiManager.
         * <p>
         * Note that when used to register a network callback, this specifies the minimum acceptable
         * signal strength. When received as the state of an existing network it specifies the
         * current value. A value of {@code SIGNAL_STRENGTH_UNSPECIFIED} means no value when
         * received and has no effect when requesting a callback.
         *
         * @param signalStrength the bearer-specific signal strength.
         * @hide
         */
        public Builder setSignalStrength(int signalStrength) {
            mNetworkCapabilities.setSignalStrength(signalStrength);
            return this;
        }
    }

    // implement the Parcelable interface
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(networkCapabilities, flags);
        dest.writeInt(legacyType);
        dest.writeInt(requestId);
    }
    public static final Creator<NetworkRequest> CREATOR =
        new Creator<NetworkRequest>() {
            public NetworkRequest createFromParcel(Parcel in) {
                NetworkCapabilities nc = (NetworkCapabilities)in.readParcelable(null);
                int legacyType = in.readInt();
                int requestId = in.readInt();
                NetworkRequest result = new NetworkRequest(nc, legacyType, requestId);
                return result;
            }
            public NetworkRequest[] newArray(int size) {
                return new NetworkRequest[size];
            }
        };

    public String toString() {
        return "NetworkRequest [ id=" + requestId + ", legacyType=" + legacyType +
                ", " + networkCapabilities.toString() + " ]";
    }

    public boolean equals(Object obj) {
        if (obj instanceof NetworkRequest == false) return false;
        NetworkRequest that = (NetworkRequest)obj;
        return (that.legacyType == this.legacyType &&
                that.requestId == this.requestId &&
                ((that.networkCapabilities == null && this.networkCapabilities == null) ||
                 (that.networkCapabilities != null &&
                  that.networkCapabilities.equals(this.networkCapabilities))));
    }

    public int hashCode() {
        return requestId + (legacyType * 1013) +
                (networkCapabilities.hashCode() * 1051);
    }
}
