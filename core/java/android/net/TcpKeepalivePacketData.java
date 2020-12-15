/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.net.InetAddress;
import java.util.Objects;

/**
 * Represents the actual tcp keep alive packets which will be used for hardware offload.
 * @hide
 */
@SystemApi
public final class TcpKeepalivePacketData extends KeepalivePacketData implements Parcelable {
    private static final String TAG = "TcpKeepalivePacketData";

    /** TCP sequence number. */
    public final int tcpSeq;

    /** TCP ACK number. */
    public final int tcpAck;

    /** TCP RCV window. */
    public final int tcpWindow;

    /** TCP RCV window scale. */
    public final int tcpWindowScale;

    /** IP TOS. */
    public final int ipTos;

    /** IP TTL. */
    public final int ipTtl;

    public TcpKeepalivePacketData(@NonNull final InetAddress srcAddress, int srcPort,
            @NonNull final InetAddress dstAddress, int dstPort, @NonNull final byte[] data,
            int tcpSeq, int tcpAck, int tcpWindow, int tcpWindowScale, int ipTos, int ipTtl)
            throws InvalidPacketException {
        super(srcAddress, srcPort, dstAddress, dstPort, data);
        this.tcpSeq = tcpSeq;
        this.tcpAck = tcpAck;
        this.tcpWindow = tcpWindow;
        this.tcpWindowScale = tcpWindowScale;
        this.ipTos = ipTos;
        this.ipTtl = ipTtl;
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (!(o instanceof TcpKeepalivePacketData)) return false;
        final TcpKeepalivePacketData other = (TcpKeepalivePacketData) o;
        final InetAddress srcAddress = getSrcAddress();
        final InetAddress dstAddress = getDstAddress();
        return srcAddress.equals(other.getSrcAddress())
                && dstAddress.equals(other.getDstAddress())
                && getSrcPort() == other.getSrcPort()
                && getDstPort() == other.getDstPort()
                && this.tcpAck == other.tcpAck
                && this.tcpSeq == other.tcpSeq
                && this.tcpWindow == other.tcpWindow
                && this.tcpWindowScale == other.tcpWindowScale
                && this.ipTos == other.ipTos
                && this.ipTtl == other.ipTtl;
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSrcAddress(), getDstAddress(), getSrcPort(), getDstPort(),
                tcpAck, tcpSeq, tcpWindow, tcpWindowScale, ipTos, ipTtl);
    }

    /**
     * Parcelable Implementation.
     * Note that this object implements parcelable (and needs to keep doing this as it inherits
     * from a class that does), but should usually be parceled as a stable parcelable using
     * the toStableParcelable() and fromStableParcelable() methods.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Write to parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(getSrcAddress().getHostAddress());
        out.writeString(getDstAddress().getHostAddress());
        out.writeInt(getSrcPort());
        out.writeInt(getDstPort());
        out.writeByteArray(getPacket());
        out.writeInt(tcpSeq);
        out.writeInt(tcpAck);
        out.writeInt(tcpWindow);
        out.writeInt(tcpWindowScale);
        out.writeInt(ipTos);
        out.writeInt(ipTtl);
    }

    private static TcpKeepalivePacketData readFromParcel(Parcel in) throws InvalidPacketException {
        InetAddress srcAddress = InetAddresses.parseNumericAddress(in.readString());
        InetAddress dstAddress = InetAddresses.parseNumericAddress(in.readString());
        int srcPort = in.readInt();
        int dstPort = in.readInt();
        byte[] packet = in.createByteArray();
        int tcpSeq = in.readInt();
        int tcpAck = in.readInt();
        int tcpWnd = in.readInt();
        int tcpWndScale = in.readInt();
        int ipTos = in.readInt();
        int ipTtl = in.readInt();
        return new TcpKeepalivePacketData(srcAddress, srcPort, dstAddress, dstPort, packet, tcpSeq,
                tcpAck, tcpWnd, tcpWndScale, ipTos, ipTtl);
    }

    /** Parcelable Creator. */
    public static final @NonNull Parcelable.Creator<TcpKeepalivePacketData> CREATOR =
            new Parcelable.Creator<TcpKeepalivePacketData>() {
                public TcpKeepalivePacketData createFromParcel(Parcel in) {
                    try {
                        return readFromParcel(in);
                    } catch (InvalidPacketException e) {
                        throw new IllegalArgumentException(
                                "Invalid TCP keepalive data: " + e.getError());
                    }
                }

                public TcpKeepalivePacketData[] newArray(int size) {
                    return new TcpKeepalivePacketData[size];
                }
            };

    @Override
    public String toString() {
        return "saddr: " + getSrcAddress()
                + " daddr: " + getDstAddress()
                + " sport: " + getSrcPort()
                + " dport: " + getDstPort()
                + " seq: " + tcpSeq
                + " ack: " + tcpAck
                + " window: " + tcpWindow
                + " windowScale: " + tcpWindowScale
                + " tos: " + ipTos
                + " ttl: " + ipTtl;
    }
}
