package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

/**
 * The IP Address Type availability ANQP Element, IEEE802.11-2012 section 8.4.4.9
 */
public class IPAddressTypeAvailabilityElement extends ANQPElement {
    public enum IPv4Availability {
        NotAvailable, Public, PortRestricted, SingleNATA, DoubleNAT,
        PortRestrictedAndSingleNAT, PortRestrictedAndDoubleNAT, Unknown
    }

    public enum IPv6Availability {NotAvailable, Available, Unknown, Reserved}

    private final IPv4Availability mV4Availability;
    private final IPv6Availability mV6Availability;

    public IPAddressTypeAvailabilityElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        if (payload.remaining() != 1)
            throw new ProtocolException("Bad IP Address Type Availability length: " +
                    payload.remaining());

        int ipField = payload.get();
        mV6Availability = IPv6Availability.values()[ipField & 0x3];

        ipField = (ipField >> 2) & 0x3f;
        mV4Availability = ipField <= IPv4Availability.values().length ?
                IPv4Availability.values()[ipField] :
                IPv4Availability.Unknown;
    }

    public IPv4Availability getV4Availability() {
        return mV4Availability;
    }

    public IPv6Availability getV6Availability() {
        return mV6Availability;
    }

    @Override
    public String toString() {
        return "IPAddressTypeAvailabilityElement{" +
                "mV4Availability=" + mV4Availability +
                ", mV6Availability=" + mV6Availability +
                '}';
    }
}
