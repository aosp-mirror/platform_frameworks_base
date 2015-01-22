package android.net.wifi.anqp.eap;

import android.net.wifi.anqp.Constants;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class VendorSpecificAuth implements AuthParam {

    private final byte[] mData;

    public VendorSpecificAuth(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 1 || payload.remaining() > 256) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        int length = payload.get() & BYTE_MASK;
        if (length > payload.remaining()) {
            throw new ProtocolException("Excessive length: " + length);
        }
        mData = new byte[length];
        payload.get(mData);
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return EAP.AuthInfoID.VendorSpecific;
    }

    public int hashCode() {
        return Arrays.hashCode(mData);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject == null || thatObject.getClass() != VendorSpecificAuth.class) {
            return false;
        } else {
            return Arrays.equals(((VendorSpecificAuth) thatObject).getData(), getData());
        }
    }

    public byte[] getData() {
        return mData;
    }

    @Override
    public String toString() {
        return "VendorSpecificAuth{" +
                "mData=" + Constants.toHexString(mData) +
                '}';
    }
}
