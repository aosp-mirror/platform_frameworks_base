package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static android.net.wifi.anqp.Constants.ANQPElementType;
import static android.net.wifi.anqp.Constants.BYTES_IN_SHORT;
import static android.net.wifi.anqp.Constants.SHORT_MASK;

/**
 * The ANQP Capability List element, 802.11-2012 section 8.4.4.3
 */
public class CapabilityListElement extends ANQPElement {
    private final ANQPElementType[] mCapabilities;

    public CapabilityListElement(ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);
        if ((payload.remaining() & 1) == 1)
            throw new ProtocolException("Odd length");
        mCapabilities = new ANQPElementType[payload.remaining() / BYTES_IN_SHORT];

        int index = 0;
        while (payload.hasRemaining()) {
            int capID = payload.getShort() & SHORT_MASK;
            ANQPElementType capability = Constants.mapANQPElement(capID);
            if (capability == null)
                throw new ProtocolException("Unknown capability: " + capID);
            mCapabilities[index++] = capability;
        }
    }

    public ANQPElementType[] getCapabilities() {
        return mCapabilities;
    }

    @Override
    public String toString() {
        return "CapabilityListElement{" +
                "mCapabilities=" + Arrays.toString(mCapabilities) +
                '}';
    }
}
