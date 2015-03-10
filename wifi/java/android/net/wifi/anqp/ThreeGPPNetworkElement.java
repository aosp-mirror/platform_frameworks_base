package android.net.wifi.anqp;

import java.nio.ByteBuffer;

/**
 * The 3GPP Cellular Network ANQP Element, IEEE802.11-2012 section 8.4.4.11
 */
public class ThreeGPPNetworkElement extends ANQPElement {

    private final byte[] mData;

    public ThreeGPPNetworkElement(Constants.ANQPElementType infoID, ByteBuffer payload) {
        super(infoID);
        mData = new byte[payload.remaining()];
        payload.get(mData);
    }

    public byte[] getData() {
        return mData;
    }

    @Override
    public String toString() {
        return "ThreeGPPNetworkElement{" +
                "mData=" + Constants.toHexString(mData) +
                '}';
    }
}
