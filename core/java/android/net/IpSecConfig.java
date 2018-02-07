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
package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * This class encapsulates all the configuration parameters needed to create IPsec transforms and
 * policies.
 *
 * @hide
 */
public final class IpSecConfig implements Parcelable {
    private static final String TAG = "IpSecConfig";

    // MODE_TRANSPORT or MODE_TUNNEL
    private int mMode = IpSecTransform.MODE_TRANSPORT;

    // Preventing this from being null simplifies Java->Native binder
    private String mSourceAddress = "";

    // Preventing this from being null simplifies Java->Native binder
    private String mDestinationAddress = "";

    // The underlying Network that represents the "gateway" Network
    // for outbound packets. It may also be used to select packets.
    private Network mNetwork;

    // Minimum requirements for identifying a transform
    // SPI identifying the IPsec SA in packet processing
    // and a destination IP address
    private int mSpiResourceId = IpSecManager.INVALID_RESOURCE_ID;

    // Encryption Algorithm
    private IpSecAlgorithm mEncryption;

    // Authentication Algorithm
    private IpSecAlgorithm mAuthentication;

    // Authenticated Encryption Algorithm
    private IpSecAlgorithm mAuthenticatedEncryption;

    // For tunnel mode IPv4 UDP Encapsulation
    // IpSecTransform#ENCAP_ESP_*, such as ENCAP_ESP_OVER_UDP_IKE
    private int mEncapType = IpSecTransform.ENCAP_NONE;
    private int mEncapSocketResourceId = IpSecManager.INVALID_RESOURCE_ID;
    private int mEncapRemotePort;

    // An interval, in seconds between the NattKeepalive packets
    private int mNattKeepaliveInterval;

    // XFRM mark and mask
    private int mMarkValue;
    private int mMarkMask;

    /** Set the mode for this IPsec transform */
    public void setMode(int mode) {
        mMode = mode;
    }

    /** Set the source IP addres for this IPsec transform */
    public void setSourceAddress(String sourceAddress) {
        mSourceAddress = sourceAddress;
    }

    /** Set the destination IP address for this IPsec transform */
    public void setDestinationAddress(String destinationAddress) {
        mDestinationAddress = destinationAddress;
    }

    /** Set the SPI by resource ID */
    public void setSpiResourceId(int resourceId) {
        mSpiResourceId = resourceId;
    }

    /** Set the encryption algorithm */
    public void setEncryption(IpSecAlgorithm encryption) {
        mEncryption = encryption;
    }

    /** Set the authentication algorithm */
    public void setAuthentication(IpSecAlgorithm authentication) {
        mAuthentication = authentication;
    }

    /** Set the authenticated encryption algorithm */
    public void setAuthenticatedEncryption(IpSecAlgorithm authenticatedEncryption) {
        mAuthenticatedEncryption = authenticatedEncryption;
    }

    /** Set the underlying network that will carry traffic for this transform */
    public void setNetwork(Network network) {
        mNetwork = network;
    }

    public void setEncapType(int encapType) {
        mEncapType = encapType;
    }

    public void setEncapSocketResourceId(int resourceId) {
        mEncapSocketResourceId = resourceId;
    }

    public void setEncapRemotePort(int port) {
        mEncapRemotePort = port;
    }

    public void setNattKeepaliveInterval(int interval) {
        mNattKeepaliveInterval = interval;
    }

    public void setMarkValue(int mark) {
        mMarkValue = mark;
    }

    public void setMarkMask(int mask) {
        mMarkMask = mask;
    }

    // Transport or Tunnel
    public int getMode() {
        return mMode;
    }

    public String getSourceAddress() {
        return mSourceAddress;
    }

    public int getSpiResourceId() {
        return mSpiResourceId;
    }

    public String getDestinationAddress() {
        return mDestinationAddress;
    }

    public IpSecAlgorithm getEncryption() {
        return mEncryption;
    }

    public IpSecAlgorithm getAuthentication() {
        return mAuthentication;
    }

    public IpSecAlgorithm getAuthenticatedEncryption() {
        return mAuthenticatedEncryption;
    }

    public Network getNetwork() {
        return mNetwork;
    }

    public int getEncapType() {
        return mEncapType;
    }

    public int getEncapSocketResourceId() {
        return mEncapSocketResourceId;
    }

    public int getEncapRemotePort() {
        return mEncapRemotePort;
    }

    public int getNattKeepaliveInterval() {
        return mNattKeepaliveInterval;
    }

    public int getMarkValue() {
        return mMarkValue;
    }

    public int getMarkMask() {
        return mMarkMask;
    }

    // Parcelable Methods

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mMode);
        out.writeString(mSourceAddress);
        out.writeString(mDestinationAddress);
        out.writeParcelable(mNetwork, flags);
        out.writeInt(mSpiResourceId);
        out.writeParcelable(mEncryption, flags);
        out.writeParcelable(mAuthentication, flags);
        out.writeParcelable(mAuthenticatedEncryption, flags);
        out.writeInt(mEncapType);
        out.writeInt(mEncapSocketResourceId);
        out.writeInt(mEncapRemotePort);
        out.writeInt(mNattKeepaliveInterval);
        out.writeInt(mMarkValue);
        out.writeInt(mMarkMask);
    }

    @VisibleForTesting
    public IpSecConfig() {}

    /** Copy constructor */
    @VisibleForTesting
    public IpSecConfig(IpSecConfig c) {
        mMode = c.mMode;
        mSourceAddress = c.mSourceAddress;
        mDestinationAddress = c.mDestinationAddress;
        mNetwork = c.mNetwork;
        mSpiResourceId = c.mSpiResourceId;
        mEncryption = c.mEncryption;
        mAuthentication = c.mAuthentication;
        mAuthenticatedEncryption = c.mAuthenticatedEncryption;
        mEncapType = c.mEncapType;
        mEncapSocketResourceId = c.mEncapSocketResourceId;
        mEncapRemotePort = c.mEncapRemotePort;
        mNattKeepaliveInterval = c.mNattKeepaliveInterval;
        mMarkValue = c.mMarkValue;
        mMarkMask = c.mMarkMask;
    }

    private IpSecConfig(Parcel in) {
        mMode = in.readInt();
        mSourceAddress = in.readString();
        mDestinationAddress = in.readString();
        mNetwork = (Network) in.readParcelable(Network.class.getClassLoader());
        mSpiResourceId = in.readInt();
        mEncryption =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        mAuthentication =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        mAuthenticatedEncryption =
                (IpSecAlgorithm) in.readParcelable(IpSecAlgorithm.class.getClassLoader());
        mEncapType = in.readInt();
        mEncapSocketResourceId = in.readInt();
        mEncapRemotePort = in.readInt();
        mNattKeepaliveInterval = in.readInt();
        mMarkValue = in.readInt();
        mMarkMask = in.readInt();
    }

    @Override
    public String toString() {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder
                .append("{mMode=")
                .append(mMode == IpSecTransform.MODE_TUNNEL ? "TUNNEL" : "TRANSPORT")
                .append(", mSourceAddress=")
                .append(mSourceAddress)
                .append(", mDestinationAddress=")
                .append(mDestinationAddress)
                .append(", mNetwork=")
                .append(mNetwork)
                .append(", mEncapType=")
                .append(mEncapType)
                .append(", mEncapSocketResourceId=")
                .append(mEncapSocketResourceId)
                .append(", mEncapRemotePort=")
                .append(mEncapRemotePort)
                .append(", mNattKeepaliveInterval=")
                .append(mNattKeepaliveInterval)
                .append("{mSpiResourceId=")
                .append(mSpiResourceId)
                .append(", mEncryption=")
                .append(mEncryption)
                .append(", mAuthentication=")
                .append(mAuthentication)
                .append(", mAuthenticatedEncryption=")
                .append(mAuthenticatedEncryption)
                .append(", mMarkValue=")
                .append(mMarkValue)
                .append(", mMarkMask=")
                .append(mMarkMask)
                .append("}");

        return strBuilder.toString();
    }

    public static final Parcelable.Creator<IpSecConfig> CREATOR =
            new Parcelable.Creator<IpSecConfig>() {
                public IpSecConfig createFromParcel(Parcel in) {
                    return new IpSecConfig(in);
                }

                public IpSecConfig[] newArray(int size) {
                    return new IpSecConfig[size];
                }
            };

    @VisibleForTesting
    /** Equals method used for testing */
    public static boolean equals(IpSecConfig lhs, IpSecConfig rhs) {
        if (lhs == null || rhs == null) return (lhs == rhs);
        return (lhs.mMode == rhs.mMode
                && lhs.mSourceAddress.equals(rhs.mSourceAddress)
                && lhs.mDestinationAddress.equals(rhs.mDestinationAddress)
                && ((lhs.mNetwork != null && lhs.mNetwork.equals(rhs.mNetwork))
                        || (lhs.mNetwork == rhs.mNetwork))
                && lhs.mEncapType == rhs.mEncapType
                && lhs.mEncapSocketResourceId == rhs.mEncapSocketResourceId
                && lhs.mEncapRemotePort == rhs.mEncapRemotePort
                && lhs.mNattKeepaliveInterval == rhs.mNattKeepaliveInterval
                && lhs.mSpiResourceId == rhs.mSpiResourceId
                && IpSecAlgorithm.equals(lhs.mEncryption, rhs.mEncryption)
                && IpSecAlgorithm.equals(
                        lhs.mAuthenticatedEncryption, rhs.mAuthenticatedEncryption)
                && IpSecAlgorithm.equals(lhs.mAuthentication, rhs.mAuthentication)
                && lhs.mMarkValue == rhs.mMarkValue
                && lhs.mMarkMask == rhs.mMarkMask);
    }
}
