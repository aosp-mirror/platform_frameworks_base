package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Domain Name ANQP Element, IEEE802.11-2012 section 8.4.4.15
 */
public class DomainNameElement extends ANQPElement {
    private final List<String> mDomains;

    public DomainNameElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);
        mDomains = new ArrayList<String>();

        while (payload.hasRemaining()) {
            // Use latin-1 to decode for now - safe for ASCII and retains encoding
            mDomains.add(Constants.getPrefixedString(payload, 1, StandardCharsets.ISO_8859_1));
        }
    }

    public List<String> getDomains() {
        return Collections.unmodifiableList(mDomains);
    }

    @Override
    public String toString() {
        return "DomainNameElement{" +
                "mDomains=" + mDomains +
                '}';
    }
}
