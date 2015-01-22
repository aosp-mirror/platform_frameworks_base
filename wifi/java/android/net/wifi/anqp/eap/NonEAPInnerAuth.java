package android.net.wifi.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class NonEAPInnerAuth implements AuthParam {

    public enum NonEAPType {Reserved, PAP, CHAP, MSCHAP, MSCHAPv2}

    private final NonEAPType mType;

    public NonEAPInnerAuth(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() != 1) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        int typeID = payload.get() & BYTE_MASK;
        mType = typeID < NonEAPType.values().length ?
                NonEAPType.values()[typeID] :
                NonEAPType.Reserved;
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return EAP.AuthInfoID.NonEAPInnerAuthType;
    }

    public NonEAPType getType() {
        return mType;
    }

    @Override
    public int hashCode() {
        return mType.hashCode();
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject == null || thatObject.getClass() != NonEAPInnerAuth.class) {
            return false;
        } else {
            return ((NonEAPInnerAuth) thatObject).getType() == getType();
        }
    }

    @Override
    public String toString() {
        return "NonEAPInnerAuth{" +
                "mType=" + mType +
                '}';
    }
}
