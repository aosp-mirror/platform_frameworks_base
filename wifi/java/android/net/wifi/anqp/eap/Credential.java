package android.net.wifi.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class Credential implements AuthParam {

    public enum CredType {
        Reserved,
        SIM,
        USIM,
        NFC,
        HWToken,
        Softoken,
        Certificate,
        Username,
        None,
        Anonymous,
        VendorSpecific}

    private final EAP.AuthInfoID mAuthInfoID;
    private final CredType mCredType;

    public Credential(EAP.AuthInfoID infoID, ByteBuffer payload) throws ProtocolException {
        mAuthInfoID = infoID;

        if (payload.remaining() != 1) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        int typeID = payload.get() & BYTE_MASK;
        mCredType = typeID < CredType.values().length ?
                CredType.values()[typeID] :
                CredType.Reserved;
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return mAuthInfoID;
    }

    @Override
    public int hashCode() {
        return mAuthInfoID.hashCode() * 31 + mCredType.hashCode();
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject == null || thatObject.getClass() != AuthParam.class) {
            return false;
        } else {
            return ((Credential) thatObject).getCredType() == getCredType();
        }
    }

    public CredType getCredType() {
        return mCredType;
    }

    @Override
    public String toString() {
        return "Credential{" +
                "mAuthInfoID=" + mAuthInfoID +
                ", mCredType=" + mCredType +
                '}';
    }
}
