package android.net.wifi.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

import static android.net.wifi.anqp.Constants.INT_MASK;
import static android.net.wifi.anqp.Constants.getInteger;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class ExpandedEAPMethod implements AuthParam {

    private final EAP.AuthInfoID m_authInfoID;
    private final int m_vendorID;
    private final long m_vendorType;

    public ExpandedEAPMethod(EAP.AuthInfoID authInfoID, ByteBuffer payload) throws ProtocolException {
        m_authInfoID = authInfoID;
        if (payload.remaining() != 7) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        m_vendorID = (int) getInteger(payload, 3);
        m_vendorType = payload.getInt() & INT_MASK;
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return m_authInfoID;
    }

    @Override
    public int hashCode() {
        return (m_authInfoID.hashCode() * 31 + m_vendorID) * 31 + (int) m_vendorType;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject == null || thatObject.getClass() != ExpandedEAPMethod.class) {
            return false;
        } else {
            ExpandedEAPMethod that = (ExpandedEAPMethod) thatObject;
            return that.getVendorID() == getVendorID() && that.getVendorType() == getVendorType();
        }
    }

    public int getVendorID() {
        return m_vendorID;
    }

    public long getVendorType() {
        return m_vendorType;
    }

    @Override
    public String toString() {
        return "ExpandedEAPMethod{" +
                "m_authInfoID=" + m_authInfoID +
                ", m_vendorID=" + m_vendorID +
                ", m_vendorType=" + m_vendorType +
                '}';
    }
}
