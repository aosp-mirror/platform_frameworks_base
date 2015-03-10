package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * The HS Capability list vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.2
 */
public class HSCapabilityListElement extends ANQPElement {
    private final Constants.ANQPElementType[] mCapabilities;

    public HSCapabilityListElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mCapabilities = new Constants.ANQPElementType[payload.remaining()];

        int index = 0;
        while (payload.hasRemaining()) {
            int capID = payload.get() & BYTE_MASK;
            Constants.ANQPElementType capability = Constants.mapANQPElement(capID);
            if (capability == null)
                throw new ProtocolException("Unknown capability: " + capID);
            mCapabilities[index++] = capability;
        }
    }

    public Constants.ANQPElementType[] getCapabilities() {
        return mCapabilities;
    }

    @Override
    public String toString() {
        return "HSCapabilityListElement{" +
                "mCapabilities=" + Arrays.toString(mCapabilities) +
                '}';
    }
}
