package android.net.wifi.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class InnerAuthEAP implements AuthParam {

    private final EAP.EAPMethodID mEapMethodID;

    public InnerAuthEAP(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() != 1) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        int typeID = payload.get() & BYTE_MASK;
        mEapMethodID = EAP.mapEAPMethod(typeID);
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return EAP.AuthInfoID.InnerAuthEAPMethodType;
    }

    public EAP.EAPMethodID getEAPMethodID() {
        return mEapMethodID;
    }

    @Override
    public int hashCode() {
        return mEapMethodID.hashCode();
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject == null || thatObject.getClass() != InnerAuthEAP.class) {
            return false;
        } else {
            return ((InnerAuthEAP) thatObject).getEAPMethodID() == getEAPMethodID();
        }
    }

    @Override
    public String toString() {
        return "InnerAuthEAP{" +
                "EapMethodID=" + mEapMethodID +
                '}';
    }
}
