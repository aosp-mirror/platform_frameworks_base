package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.net.wifi.anqp.Constants.BYTE_MASK;
import static android.net.wifi.anqp.Constants.getInteger;

/**
 * The Roaming Consortium ANQP Element, IEEE802.11-2012 section 8.4.4.7
 */
public class RoamingConsortiumElement extends ANQPElement {

    private final List<Long> mOis;

    public RoamingConsortiumElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mOis = new ArrayList<Long>();

        while (payload.hasRemaining()) {
            int length = payload.get() & BYTE_MASK;
            if (length > payload.remaining()) {
                throw new ProtocolException("Bad OI length: " + length);
            }
            mOis.add(getInteger(payload, length));
        }
    }

    public List<Long> getOIs() {
        return Collections.unmodifiableList(mOis);
    }

    @Override
    public String toString() {
        return "RoamingConsortiumElement{" +
                "mOis=" + mOis +
                '}';
    }
}
