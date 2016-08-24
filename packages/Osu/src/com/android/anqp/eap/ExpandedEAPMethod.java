package com.android.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.android.anqp.Constants.BYTE_MASK;
import static com.android.anqp.Constants.INT_MASK;
import static com.android.anqp.Constants.SHORT_MASK;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class ExpandedEAPMethod implements AuthParam {

    private final EAP.AuthInfoID mAuthInfoID;
    private final int mVendorID;
    private final long mVendorType;

    public ExpandedEAPMethod(EAP.AuthInfoID authInfoID, int length, ByteBuffer payload)
            throws ProtocolException {
        if (length != 7) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        mAuthInfoID = authInfoID;

        ByteBuffer vndBuffer = payload.duplicate().order(ByteOrder.BIG_ENDIAN);

        int id = vndBuffer.getShort() & SHORT_MASK;
        id = (id << Byte.SIZE) | (vndBuffer.get() & BYTE_MASK);
        mVendorID = id;
        mVendorType = vndBuffer.getInt() & INT_MASK;

        payload.position(payload.position()+7);
    }

    public ExpandedEAPMethod(EAP.AuthInfoID authInfoID, int vendorID, long vendorType) {
        mAuthInfoID = authInfoID;
        mVendorID = vendorID;
        mVendorType = vendorType;
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return mAuthInfoID;
    }

    @Override
    public int hashCode() {
        return (mAuthInfoID.hashCode() * 31 + mVendorID) * 31 + (int) mVendorType;
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
        return mVendorID;
    }

    public long getVendorType() {
        return mVendorType;
    }

    @Override
    public String toString() {
        return "Auth method " + mAuthInfoID + ", id " + mVendorID + ", type " + mVendorType + "\n";
    }
}
