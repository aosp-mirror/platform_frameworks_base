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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.net.NetworkCapabilities.NetCapability;
import android.net.NetworkCapabilities.Transport;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.proto.ProtoOutputStream;

import java.util.Objects;
import java.util.Set;

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
    @UnsupportedAppUsage
    public final @NonNull NetworkCapabilities networkCapabilities;

    /**
     * Identifies the request.  NetworkRequests should only be constructed by
     * the Framework and given out to applications as tokens to be used to identify
     * the request.
     * @hide
     */
    @UnsupportedAppUsage
    public final int requestId;

    /**
     * Set for legacy requests and the default.  Set to TYPE_NONE for none.
     * Causes CONNECTIVITY_ACTION broadcasts to be sent.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public final int legacyType;

    /**
     * A NetworkRequest as used by the system can be one of the following types:
     *
     *     - LISTEN, for which the framework will issue callbacks about any
     *       and all networks that match the specified NetworkCapabilities,
     *
     *     - REQUEST, capable of causing a specific network to be created
     *       first (e.g. a telephony DUN request), the framework will issue
     *       callbacks about the single, highest scoring current network
     *       (if any) that matches the specified NetworkCapabilities, or
     *
     *     - TRACK_DEFAULT, a hybrid of the two designed such that the
     *       framework will issue callbacks for the single, highest scoring
     *       current network (if any) that matches the capabilities of the
     *       default Internet request (mDefaultRequest), but which cannot cause
     *       the framework to either create or retain the existence of any
     *       specific network. Note that from the point of view of the request
     *       matching code, TRACK_DEFAULT is identical to REQUEST: its special
     *       behaviour is not due to different semantics, but to the fact that
     *       the system will only ever create a TRACK_DEFAULT with capabilities
     *       that are identical to the default request's capabilities, thus
     *       causing it to share fate in every way with the default request.
     *
     *     - BACKGROUND_REQUEST, like REQUEST but does not cause any networks
     *       to retain the NET_CAPABILITY_FOREGROUND capability. A network with
     *       no foreground requests is in the background. A network that has
     *       one or more background requests and loses its last foreground
     *       request to a higher-scoring network will not go into the
     *       background immediately, but will linger and go into the background
     *       after the linger timeout.
     *
     *     - The value NONE is used only by applications. When an application
     *       creates a NetworkRequest, it does not have a type; the type is set
     *       by the system depending on the method used to file the request
     *       (requestNetwork, registerNetworkCallback, etc.).
     *
     * @hide
     */
    public static enum Type {
        NONE,
        LISTEN,
        TRACK_DEFAULT,
        REQUEST,
        BACKGROUND_REQUEST,
    };

    /**
     * The type of the request. This is only used by the system and is always NONE elsewhere.
     *
     * @hide
     */
    public final Type type;

    /**
     * @hide
     */
    public NetworkRequest(NetworkCapabilities nc, int legacyType, int rId, Type type) {
        if (nc == null) {
            throw new NullPointerException();
        }
        requestId = rId;
        networkCapabilities = nc;
        this.legacyType = legacyType;
        this.type = type;
    }

    /**
     * @hide
     */
    public NetworkRequest(NetworkRequest that) {
        networkCapabilities = new NetworkCapabilities(that.networkCapabilities);
        requestId = that.requestId;
        this.legacyType = that.legacyType;
        this.type = that.type;
    }

    /**
     * Builder used to create {@link NetworkRequest} objects.  Specify the Network features
     * needed in terms of {@link NetworkCapabilities} features
     */
    public static class Builder {
        private final NetworkCapabilities mNetworkCapabilities;

        /**
         * Default constructor for Builder.
         */
        public Builder() {
            // By default, restrict this request to networks available to this app.
            // Apps can rescind this restriction, but ConnectivityService will enforce
            // it for apps that do not have the NETWORK_SETTINGS permission.
            mNetworkCapabilities = new NetworkCapabilities();
            mNetworkCapabilities.setSingleUid(Process.myUid());
        }

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
                    ConnectivityManager.REQUEST_ID_UNSET, Type.NONE);
        }

        /**
         * Add the given capability requirement to this builder.  These represent
         * the requested network's required capabilities.  Note that when searching
         * for a network to satisfy a request, all capabilities requested must be
         * satisfied.
         *
         * @param capability The capability to add.
         * @return The builder to facilitate chaining
         *         {@code builder.addCapability(...).addCapability();}.
         */
        public Builder addCapability(@NetworkCapabilities.NetCapability int capability) {
            mNetworkCapabilities.addCapability(capability);
            return this;
        }

        /**
         * Removes (if found) the given capability from this builder instance.
         *
         * @param capability The capability to remove.
         * @return The builder to facilitate chaining.
         */
        public Builder removeCapability(@NetworkCapabilities.NetCapability int capability) {
            mNetworkCapabilities.removeCapability(capability);
            return this;
        }

        /**
         * Set the {@code NetworkCapabilities} for this builder instance,
         * overriding any capabilities that had been previously set.
         *
         * @param nc The superseding {@code NetworkCapabilities} instance.
         * @return The builder to facilitate chaining.
         * @hide
         */
        public Builder setCapabilities(NetworkCapabilities nc) {
            mNetworkCapabilities.set(nc);
            return this;
        }

        /**
         * Set the watched UIDs for this request. This will be reset and wiped out unless
         * the calling app holds the CHANGE_NETWORK_STATE permission.
         *
         * @param uids The watched UIDs as a set of UidRanges, or null for everything.
         * @return The builder to facilitate chaining.
         * @hide
         */
        public Builder setUids(Set<UidRange> uids) {
            mNetworkCapabilities.setUids(uids);
            return this;
        }

        /**
         * Add a capability that must not exist in the requested network.
         * <p>
         * If the capability was previously added to the list of required capabilities (for
         * example, it was there by default or added using {@link #addCapability(int)} method), then
         * it will be removed from the list of required capabilities as well.
         *
         * @see #addCapability(int)
         *
         * @param capability The capability to add to unwanted capability list.
         * @return The builder to facilitate chaining.
         *
         * @hide
         */
        public Builder addUnwantedCapability(@NetworkCapabilities.NetCapability int capability) {
            mNetworkCapabilities.addUnwantedCapability(capability);
            return this;
        }

        /**
         * Completely clears all the {@code NetworkCapabilities} from this builder instance,
         * removing even the capabilities that are set by default when the object is constructed.
         *
         * @return The builder to facilitate chaining.
         * @hide
         */
        @UnsupportedAppUsage
        public Builder clearCapabilities() {
            mNetworkCapabilities.clearAll();
            return this;
        }

        /**
         * Adds the given transport requirement to this builder.  These represent
         * the set of allowed transports for the request.  Only networks using one
         * of these transports will satisfy the request.  If no particular transports
         * are required, none should be specified here.
         *
         * @param transportType The transport type to add.
         * @return The builder to facilitate chaining.
         */
        public Builder addTransportType(@NetworkCapabilities.Transport int transportType) {
            mNetworkCapabilities.addTransportType(transportType);
            return this;
        }

        /**
         * Removes (if found) the given transport from this builder instance.
         *
         * @param transportType The transport type to remove.
         * @return The builder to facilitate chaining.
         */
        public Builder removeTransportType(@NetworkCapabilities.Transport int transportType) {
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
            /*
             * A StringNetworkSpecifier does not accept null or empty ("") strings. When network
             * specifiers were strings a null string and an empty string were considered equivalent.
             * Hence no meaning is attached to a null or empty ("") string.
             */
            return setNetworkSpecifier(TextUtils.isEmpty(networkSpecifier) ? null
                    : new StringNetworkSpecifier(networkSpecifier));
        }

        /**
         * Sets the optional bearer specific network specifier.
         * This has no meaning if a single transport is also not specified, so calling
         * this without a single transport set will generate an exception, as will
         * subsequently adding or removing transports after this is set.
         * </p>
         *
         * @param networkSpecifier A concrete, parcelable framework class that extends
         *                         NetworkSpecifier.
         */
        public Builder setNetworkSpecifier(NetworkSpecifier networkSpecifier) {
            MatchAllNetworkSpecifier.checkNotMatchAllNetworkSpecifier(networkSpecifier);
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
         * <p>This method requires the caller to hold the
         * {@link android.Manifest.permission#NETWORK_SIGNAL_STRENGTH_WAKEUP} permission
         *
         * @param signalStrength the bearer-specific signal strength.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.NETWORK_SIGNAL_STRENGTH_WAKEUP)
        public @NonNull Builder setSignalStrength(int signalStrength) {
            mNetworkCapabilities.setSignalStrength(signalStrength);
            return this;
        }
    }

    // implement the Parcelable interface
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(Parcel dest, int flags) {
        networkCapabilities.writeToParcel(dest, flags);
        dest.writeInt(legacyType);
        dest.writeInt(requestId);
        dest.writeString(type.name());
    }
    public static final @android.annotation.NonNull Creator<NetworkRequest> CREATOR =
        new Creator<NetworkRequest>() {
            public NetworkRequest createFromParcel(Parcel in) {
                NetworkCapabilities nc = NetworkCapabilities.CREATOR.createFromParcel(in);
                int legacyType = in.readInt();
                int requestId = in.readInt();
                Type type = Type.valueOf(in.readString());  // IllegalArgumentException if invalid.
                NetworkRequest result = new NetworkRequest(nc, legacyType, requestId, type);
                return result;
            }
            public NetworkRequest[] newArray(int size) {
                return new NetworkRequest[size];
            }
        };

    /**
     * Returns true iff. this NetworkRequest is of type LISTEN.
     *
     * @hide
     */
    public boolean isListen() {
        return type == Type.LISTEN;
    }

    /**
     * Returns true iff. the contained NetworkRequest is one that:
     *
     *     - should be associated with at most one satisfying network
     *       at a time;
     *
     *     - should cause a network to be kept up, but not necessarily in
     *       the foreground, if it is the best network which can satisfy the
     *       NetworkRequest.
     *
     * For full detail of how isRequest() is used for pairing Networks with
     * NetworkRequests read rematchNetworkAndRequests().
     *
     * @hide
     */
    public boolean isRequest() {
        return isForegroundRequest() || isBackgroundRequest();
    }

    /**
     * Returns true iff. the contained NetworkRequest is one that:
     *
     *     - should be associated with at most one satisfying network
     *       at a time;
     *
     *     - should cause a network to be kept up and in the foreground if
     *       it is the best network which can satisfy the NetworkRequest.
     *
     * For full detail of how isRequest() is used for pairing Networks with
     * NetworkRequests read rematchNetworkAndRequests().
     *
     * @hide
     */
    public boolean isForegroundRequest() {
        return type == Type.TRACK_DEFAULT || type == Type.REQUEST;
    }

    /**
     * Returns true iff. this NetworkRequest is of type BACKGROUND_REQUEST.
     *
     * @hide
     */
    public boolean isBackgroundRequest() {
        return type == Type.BACKGROUND_REQUEST;
    }

    /**
     * @see Builder#addCapability(int)
     */
    public boolean hasCapability(@NetCapability int capability) {
        return networkCapabilities.hasCapability(capability);
    }

    /**
     * @see Builder#addUnwantedCapability(int)
     *
     * @hide
     */
    public boolean hasUnwantedCapability(@NetCapability int capability) {
        return networkCapabilities.hasUnwantedCapability(capability);
    }

    /**
     * @see Builder#addTransportType(int)
     */
    public boolean hasTransport(@Transport int transportType) {
        return networkCapabilities.hasTransport(transportType);
    }

    public String toString() {
        return "NetworkRequest [ " + type + " id=" + requestId +
                (legacyType != ConnectivityManager.TYPE_NONE ? ", legacyType=" + legacyType : "") +
                ", " + networkCapabilities.toString() + " ]";
    }

    private int typeToProtoEnum(Type t) {
        switch (t) {
            case NONE:
                return NetworkRequestProto.TYPE_NONE;
            case LISTEN:
                return NetworkRequestProto.TYPE_LISTEN;
            case TRACK_DEFAULT:
                return NetworkRequestProto.TYPE_TRACK_DEFAULT;
            case REQUEST:
                return NetworkRequestProto.TYPE_REQUEST;
            case BACKGROUND_REQUEST:
                return NetworkRequestProto.TYPE_BACKGROUND_REQUEST;
            default:
                return NetworkRequestProto.TYPE_UNKNOWN;
        }
    }

    /** @hide */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(NetworkRequestProto.TYPE, typeToProtoEnum(type));
        proto.write(NetworkRequestProto.REQUEST_ID, requestId);
        proto.write(NetworkRequestProto.LEGACY_TYPE, legacyType);
        networkCapabilities.writeToProto(proto, NetworkRequestProto.NETWORK_CAPABILITIES);

        proto.end(token);
    }

    public boolean equals(Object obj) {
        if (obj instanceof NetworkRequest == false) return false;
        NetworkRequest that = (NetworkRequest)obj;
        return (that.legacyType == this.legacyType &&
                that.requestId == this.requestId &&
                that.type == this.type &&
                Objects.equals(that.networkCapabilities, this.networkCapabilities));
    }

    public int hashCode() {
        return Objects.hash(requestId, legacyType, networkCapabilities, type);
    }
}
