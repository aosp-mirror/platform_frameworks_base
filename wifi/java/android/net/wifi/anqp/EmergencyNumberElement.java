package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Emergency Number ANQP Element, IEEE802.11-2012 section 8.4.4.5
 */
public class EmergencyNumberElement extends ANQPElement {
    private final List<String> mNumbers;

    public EmergencyNumberElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mNumbers = new ArrayList<String>();

        while (payload.hasRemaining()) {
            mNumbers.add(Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8));
        }
    }

    public List<String> getNumbers() {
        return mNumbers;
    }

    @Override
    public String toString() {
        return "EmergencyNumberElement{" +
                "mNumbers=" + mNumbers +
                '}';
    }
}
