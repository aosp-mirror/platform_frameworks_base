/**
 * Copyright 2020 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.InetAddresses;
import android.net.LinkAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class that stores QOS filter parameters as defined in
 * 3gpp 24.008 10.5.6.12 and 3gpp 24.501 9.11.4.13.
 *
 * @hide
 */
public final class QosBearerFilter implements Parcelable {

    private List<LinkAddress> localAddresses;
    private List<LinkAddress> remoteAddresses;
    private PortRange localPort;
    private PortRange remotePort;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "QOS_PROTOCOL_",
            value = {QOS_PROTOCOL_UNSPECIFIED, QOS_PROTOCOL_TCP, QOS_PROTOCOL_UDP,
                    QOS_PROTOCOL_ESP, QOS_PROTOCOL_AH})
    public @interface QosProtocol {}

    public static final int QOS_PROTOCOL_UNSPECIFIED =
            android.hardware.radio.V1_6.QosProtocol.UNSPECIFIED;
    public static final int QOS_PROTOCOL_TCP = android.hardware.radio.V1_6.QosProtocol.TCP;
    public static final int QOS_PROTOCOL_UDP = android.hardware.radio.V1_6.QosProtocol.UDP;
    public static final int QOS_PROTOCOL_ESP = android.hardware.radio.V1_6.QosProtocol.ESP;
    public static final int QOS_PROTOCOL_AH = android.hardware.radio.V1_6.QosProtocol.AH;

    @QosProtocol
    private int protocol;

    private int typeOfServiceMask;

    private long flowLabel;

    /** IPSec security parameter index */
    private long securityParameterIndex;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "QOS_FILTER_DIRECTION_",
            value = {QOS_FILTER_DIRECTION_DOWNLINK, QOS_FILTER_DIRECTION_UPLINK,
                    QOS_FILTER_DIRECTION_BIDIRECTIONAL})
    public @interface QosBearerFilterDirection {}

    public static final int QOS_FILTER_DIRECTION_DOWNLINK =
            android.hardware.radio.V1_6.QosFilterDirection.DOWNLINK;
    public static final int QOS_FILTER_DIRECTION_UPLINK =
            android.hardware.radio.V1_6.QosFilterDirection.UPLINK;
    public static final int QOS_FILTER_DIRECTION_BIDIRECTIONAL =
            android.hardware.radio.V1_6.QosFilterDirection.BIDIRECTIONAL;

    @QosBearerFilterDirection
    private int filterDirection;

    /**
     * Specified the order in which the filter needs to be matched.
     * A Lower numerical value has a higher precedence.
     */
    private int precedence;

    QosBearerFilter() {
        localAddresses = new ArrayList<>();
        remoteAddresses = new ArrayList<>();
        localPort = new PortRange();
        remotePort = new PortRange();
        protocol = QOS_PROTOCOL_UNSPECIFIED;
        filterDirection = QOS_FILTER_DIRECTION_BIDIRECTIONAL;
    }

    public QosBearerFilter(List<LinkAddress> localAddresses, List<LinkAddress> remoteAddresses,
            PortRange localPort, PortRange remotePort, int protocol, int tos,
            long flowLabel, long spi, int direction, int precedence) {
        this.localAddresses = localAddresses;
        this.remoteAddresses = remoteAddresses;
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.protocol = protocol;
        this.typeOfServiceMask = tos;
        this.flowLabel = flowLabel;
        this.securityParameterIndex = spi;
        this.filterDirection = direction;
        this.precedence = precedence;
    }

    public List<LinkAddress> getLocalAddresses() {
        return localAddresses;
    }

    public List<LinkAddress> getRemoteAddresses() {
        return remoteAddresses;
    }

    public PortRange getLocalPortRange() {
        return localPort;
    }

    public PortRange getRemotePortRange() {
        return remotePort;
    }

    public int getPrecedence() {
        return precedence;
    }

    /** @hide */
    public static @NonNull QosBearerFilter create(
            @NonNull android.hardware.radio.V1_6.QosFilter qosFilter) {
        QosBearerFilter ret = new QosBearerFilter();

        String[] localAddresses = qosFilter.localAddresses.stream().toArray(String[]::new);
        if (localAddresses != null) {
            for (String address : localAddresses) {
                ret.localAddresses.add(createLinkAddressFromString(address));
            }
        }

        String[] remoteAddresses = qosFilter.remoteAddresses.stream().toArray(String[]::new);
        if (remoteAddresses != null) {
            for (String address : remoteAddresses) {
                ret.remoteAddresses.add(createLinkAddressFromString(address));
            }
        }

        if (qosFilter.localPort != null) {
            if (qosFilter.localPort.getDiscriminator()
                    == android.hardware.radio.V1_6.MaybePort.hidl_discriminator.range) {
                final android.hardware.radio.V1_6.PortRange portRange = qosFilter.localPort.range();
                ret.localPort.start = portRange.start;
                ret.localPort.end = portRange.end;
            }
        }

        if (qosFilter.remotePort != null) {
            if (qosFilter.remotePort.getDiscriminator()
                    == android.hardware.radio.V1_6.MaybePort.hidl_discriminator.range) {
                final android.hardware.radio.V1_6.PortRange portRange
                        = qosFilter.remotePort.range();
                ret.remotePort.start = portRange.start;
                ret.remotePort.end = portRange.end;
            }
        }

        ret.protocol = qosFilter.protocol;

        if (qosFilter.tos != null) {
            if (qosFilter.tos.getDiscriminator()
                == android.hardware.radio.V1_6.QosFilter.TypeOfService.hidl_discriminator.value) {
                ret.typeOfServiceMask = qosFilter.tos.value();
            }
        }

        if (qosFilter.flowLabel != null) {
            if (qosFilter.flowLabel.getDiscriminator()
                == android.hardware.radio.V1_6.QosFilter.Ipv6FlowLabel.hidl_discriminator.value) {
                ret.flowLabel = qosFilter.flowLabel.value();
            }
        }

        if (qosFilter.spi != null) {
            if (qosFilter.spi.getDiscriminator()
                == android.hardware.radio.V1_6.QosFilter.IpsecSpi.hidl_discriminator.value) {
                ret.securityParameterIndex = qosFilter.spi.value();
            }
        }

        ret.filterDirection = qosFilter.direction;
        ret.precedence = qosFilter.precedence;

        return ret;
    }

    public static class PortRange implements Parcelable {
        int start;
        int end;

        PortRange() {
            start = -1;
            end = -1;
        }

        private PortRange(Parcel source) {
            start = source.readInt();
            end = source.readInt();
        }

        public PortRange(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(start);
            dest.writeInt(end);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @NonNull Parcelable.Creator<PortRange> CREATOR =
                new Parcelable.Creator<PortRange>() {
                    @Override
                    public PortRange createFromParcel(Parcel source) {
                        return new PortRange(source);
                    }

                    @Override
                    public PortRange[] newArray(int size) {
                        return new PortRange[size];
                    }
                };

        @Override
        public String toString() {
            return "PortRange {"
                    + " start=" + start
                    + " end=" + end + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;

            if (o == null || !(o instanceof PortRange)) {
              return false;
            }

            PortRange other = (PortRange) o;
            return start == other.start
                    && end == other.end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }
    };

    @Override
    public String toString() {
        return "QosBearerFilter {"
                + " localAddresses=" + localAddresses
                + " remoteAddresses=" + remoteAddresses
                + " localPort=" + localPort
                + " remotePort=" + remotePort
                + " protocol=" + protocol
                + " typeOfServiceMask=" + typeOfServiceMask
                + " flowLabel=" + flowLabel
                + " securityParameterIndex=" + securityParameterIndex
                + " filterDirection=" + filterDirection
                + " precedence=" + precedence + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(localAddresses, remoteAddresses, localPort,
                remotePort, protocol, typeOfServiceMask, flowLabel,
                securityParameterIndex, filterDirection, precedence);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof QosBearerFilter)) {
            return false;
        }

        QosBearerFilter other = (QosBearerFilter) o;

        return localAddresses.size() == other.localAddresses.size()
                && localAddresses.containsAll(other.localAddresses)
                && remoteAddresses.size() == other.remoteAddresses.size()
                && remoteAddresses.containsAll(other.remoteAddresses)
                && localPort.equals(other.localPort)
                && remotePort.equals(other.remotePort)
                && protocol == other.protocol
                && typeOfServiceMask == other.typeOfServiceMask
                && flowLabel == other.flowLabel
                && securityParameterIndex == other.securityParameterIndex
                && filterDirection == other.filterDirection
                && precedence == other.precedence;
    }

    private static LinkAddress createLinkAddressFromString(String addressString) {
        addressString = addressString.trim();
        InetAddress address = null;
        int prefixLength = -1;
        try {
            String[] pieces = addressString.split("/", 2);
            address = InetAddresses.parseNumericAddress(pieces[0]);
            if (pieces.length == 1) {
                prefixLength = (address instanceof Inet4Address) ? 32 : 128;
            } else if (pieces.length == 2) {
                prefixLength = Integer.parseInt(pieces[1]);
            }
        } catch (NullPointerException e) {            // Null string.
        } catch (ArrayIndexOutOfBoundsException e) {  // No prefix length.
        } catch (NumberFormatException e) {           // Non-numeric prefix.
        } catch (IllegalArgumentException e) {        // Invalid IP address.
        }

        if (address == null || prefixLength == -1) {
            throw new IllegalArgumentException("Invalid link address " + addressString);
        }

        return new LinkAddress(address, prefixLength, 0, 0,
                LinkAddress.LIFETIME_UNKNOWN, LinkAddress.LIFETIME_UNKNOWN);
    }

    private QosBearerFilter(Parcel source) {
        localAddresses = new ArrayList<>();
        source.readList(localAddresses, LinkAddress.class.getClassLoader());
        remoteAddresses = new ArrayList<>();
        source.readList(remoteAddresses, LinkAddress.class.getClassLoader());
        localPort = source.readParcelable(PortRange.class.getClassLoader());
        remotePort = source.readParcelable(PortRange.class.getClassLoader());
        protocol = source.readInt();
        typeOfServiceMask = source.readInt();
        flowLabel = source.readLong();
        securityParameterIndex = source.readLong();
        filterDirection = source.readInt();
        precedence = source.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeList(localAddresses);
        dest.writeList(remoteAddresses);
        dest.writeParcelable(localPort, flags);
        dest.writeParcelable(remotePort, flags);
        dest.writeInt(protocol);
        dest.writeInt(typeOfServiceMask);
        dest.writeLong(flowLabel);
        dest.writeLong(securityParameterIndex);
        dest.writeInt(filterDirection);
        dest.writeInt(precedence);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<QosBearerFilter> CREATOR =
            new Parcelable.Creator<QosBearerFilter>() {
                @Override
                public QosBearerFilter createFromParcel(Parcel source) {
                    return new QosBearerFilter(source);
                }

                @Override
                public QosBearerFilter[] newArray(int size) {
                    return new QosBearerFilter[size];
                }
            };
}
