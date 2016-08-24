package com.android.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class VendorSpecificAuth implements AuthParam {

    private final byte[] mData;

    public VendorSpecificAuth(int length, ByteBuffer payload) throws ProtocolException {
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
        return "Auth method VendorSpecificAuth, data = " + Arrays.toString(mData) + '\n';
    }
}
