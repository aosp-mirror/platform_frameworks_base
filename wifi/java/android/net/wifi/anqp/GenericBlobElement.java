package android.net.wifi.anqp;

import java.nio.ByteBuffer;

/**
 * ANQP Element to hold a raw, unparsed, octet blob
 */
public class GenericBlobElement extends ANQPElement {
    private final byte[] mData;

    public GenericBlobElement(Constants.ANQPElementType infoID, ByteBuffer payload) {
        super(infoID);
        mData = new byte[payload.remaining()];
        payload.get(mData);
    }

    public byte[] getData() {
        return mData;
    }

    @Override
    public String toString() {
        return "Element ID " + getID() + ": " + Constants.toHexString(mData);
    }
}
