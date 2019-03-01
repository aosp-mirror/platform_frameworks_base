/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi.aware;

import android.net.NetworkSpecifier;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Network specifier object used to request a Wi-Fi Aware network. Apps do not create these objects
 * directly but obtain them using
 * {@link WifiAwareSession#createNetworkSpecifierOpen(int, byte[])} or
 * {@link DiscoverySession#createNetworkSpecifierOpen(PeerHandle)} or their secure (Passphrase)
 * versions.
 *
 * @hide
 */
public final class WifiAwareNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    /**
     * TYPE: in band, specific peer: role, client_id, session_id, peer_id, pmk/passphrase optional
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_IB = 0;

    /**
     * TYPE: in band, any peer: role, client_id, session_id, pmk/passphrase optional
     * [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_IB_ANY_PEER = 1;

    /**
     * TYPE: out-of-band: role, client_id, peer_mac, pmk/passphrase optional
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_OOB = 2;

    /**
     * TYPE: out-of-band, any peer: role, client_id, pmk/passphrase optional
     * [only permitted for RESPONDER]
     * @hide
     */
    public static final int NETWORK_SPECIFIER_TYPE_OOB_ANY_PEER = 3;

    /** @hide */
    public static final int NETWORK_SPECIFIER_TYPE_MAX_VALID = NETWORK_SPECIFIER_TYPE_OOB_ANY_PEER;

    /**
     * One of the NETWORK_SPECIFIER_TYPE_* constants. The type of the network specifier object.
     * @hide
     */
    public final int type;

    /**
     * The role of the device: WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR or
     * WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER.
     * @hide
     */
    public final int role;

    /**
     * The client ID of the device.
     * @hide
     */
    public final int clientId;

    /**
     * The session ID in which context to request a data-path. Only relevant for IB requests.
     * @hide
     */
    public final int sessionId;

    /**
     * The peer ID of the device which the data-path should be connected to. Only relevant for
     * IB requests (i.e. not IB_ANY_PEER or OOB*).
     * @hide
     */
    public final int peerId;

    /**
     * The peer MAC address of the device which the data-path should be connected to. Only relevant
     * for OB requests (i.e. not OOB_ANY_PEER or IB*).
     * @hide
     */
    public final byte[] peerMac;

    /**
     * The PMK of the requested data-path. Can be null. Only one or none of pmk or passphrase should
     * be specified.
     * @hide
     */
    public final byte[] pmk;

    /**
     * The Passphrase of the requested data-path. Can be null. Only one or none of the pmk or
     * passphrase should be specified.
     * @hide
     */
    public final String passphrase;

    /**
     * The port information to be used for this link. This information will be communicated to the
     * peer as part of the layer 2 link setup.
     *
     * Information only allowed on secure links since a single layer-2 link is set up for all
     * requestors. Therefore if multiple apps on a single device request links to the same peer
     * device they all get the same link. However, the link is only set up on the first request -
     * hence only the first can transmit the port information. But we don't want to expose that
     * information to other apps. Limiting to secure links would (usually) imply single app usage.
     *
     * @hide
     */
    public final int port;

    /**
     * The transport protocol information to be used for this link. This information will be
     * communicated to the peer as part of the layer 2 link setup.
     *
     * Information only allowed on secure links since a single layer-2 link is set up for all
     * requestors. Therefore if multiple apps on a single device request links to the same peer
     * device they all get the same link. However, the link is only set up on the first request -
     * hence only the first can transmit the port information. But we don't want to expose that
     * information to other apps. Limiting to secure links would (usually) imply single app usage.
     */
    public final int transportProtocol;

    /**
     * The UID of the process initializing this network specifier. Validated by receiver using
     * checkUidIfNecessary() and is used by satisfiedBy() to determine whether matches the
     * offered network.
     *
     * @hide
     */
    public final int requestorUid;

    /** @hide */
    public WifiAwareNetworkSpecifier(int type, int role, int clientId, int sessionId, int peerId,
            byte[] peerMac, byte[] pmk, String passphrase, int port, int transportProtocol,
            int requestorUid) {
        this.type = type;
        this.role = role;
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.peerId = peerId;
        this.peerMac = peerMac;
        this.pmk = pmk;
        this.passphrase = passphrase;
        this.port = port;
        this.transportProtocol = transportProtocol;
        this.requestorUid = requestorUid;
    }

    public static final @android.annotation.NonNull Creator<WifiAwareNetworkSpecifier> CREATOR =
            new Creator<WifiAwareNetworkSpecifier>() {
                @Override
                public WifiAwareNetworkSpecifier createFromParcel(Parcel in) {
                    return new WifiAwareNetworkSpecifier(
                        in.readInt(), // type
                        in.readInt(), // role
                        in.readInt(), // clientId
                        in.readInt(), // sessionId
                        in.readInt(), // peerId
                        in.createByteArray(), // peerMac
                        in.createByteArray(), // pmk
                        in.readString(), // passphrase
                        in.readInt(), // port
                        in.readInt(), // transportProtocol
                        in.readInt()); // requestorUid
                }

                @Override
                public WifiAwareNetworkSpecifier[] newArray(int size) {
                    return new WifiAwareNetworkSpecifier[size];
                }
            };

    /**
     * Indicates whether the network specifier specifies an OOB (out-of-band) data-path - i.e. a
     * data-path created without a corresponding Aware discovery session.
     *
     * @hide
     */
    public boolean isOutOfBand() {
        return type == NETWORK_SPECIFIER_TYPE_OOB || type == NETWORK_SPECIFIER_TYPE_OOB_ANY_PEER;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeInt(role);
        dest.writeInt(clientId);
        dest.writeInt(sessionId);
        dest.writeInt(peerId);
        dest.writeByteArray(peerMac);
        dest.writeByteArray(pmk);
        dest.writeString(passphrase);
        dest.writeInt(port);
        dest.writeInt(transportProtocol);
        dest.writeInt(requestorUid);
    }

    /** @hide */
    @Override
    public boolean satisfiedBy(NetworkSpecifier other) {
        // MatchAllNetworkSpecifier is taken care in NetworkCapabilities#satisfiedBySpecifier.
        if (other instanceof WifiAwareAgentNetworkSpecifier) {
            return ((WifiAwareAgentNetworkSpecifier) other).satisfiesAwareNetworkSpecifier(this);
        }
        return equals(other);
    }

    /** @hide */
    @Override
    public int hashCode() {
        return Objects.hash(type, role, clientId, sessionId, peerId, Arrays.hashCode(peerMac),
                Arrays.hashCode(pmk), passphrase, port, transportProtocol, requestorUid);
    }

    /** @hide */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof WifiAwareNetworkSpecifier)) {
            return false;
        }

        WifiAwareNetworkSpecifier lhs = (WifiAwareNetworkSpecifier) obj;

        return type == lhs.type
                && role == lhs.role
                && clientId == lhs.clientId
                && sessionId == lhs.sessionId
                && peerId == lhs.peerId
                && Arrays.equals(peerMac, lhs.peerMac)
                && Arrays.equals(pmk, lhs.pmk)
                && Objects.equals(passphrase, lhs.passphrase)
                && port == lhs.port
                && transportProtocol == lhs.transportProtocol
                && requestorUid == lhs.requestorUid;
    }

    /** @hide */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WifiAwareNetworkSpecifier [");
        sb.append("type=").append(type)
                .append(", role=").append(role)
                .append(", clientId=").append(clientId)
                .append(", sessionId=").append(sessionId)
                .append(", peerId=").append(peerId)
                // masking potential PII (although low impact information)
                .append(", peerMac=").append((peerMac == null) ? "<null>" : "<non-null>")
                // masking PII
                .append(", pmk=").append((pmk == null) ? "<null>" : "<non-null>")
                // masking PII
                .append(", passphrase=").append((passphrase == null) ? "<null>" : "<non-null>")
                .append(", port=").append(port).append(", transportProtocol=")
                .append(transportProtocol).append(", requestorUid=").append(requestorUid)
                .append("]");
        return sb.toString();
    }

    /** @hide */
    @Override
    public void assertValidFromUid(int requestorUid) {
        if (this.requestorUid != requestorUid) {
            throw new SecurityException("mismatched UIDs");
        }
    }
}
