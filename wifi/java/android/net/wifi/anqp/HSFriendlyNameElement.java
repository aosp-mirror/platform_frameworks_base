package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Operator Friendly Name vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.3
 */
public class HSFriendlyNameElement extends ANQPElement {
    private final List<I18Name> mNames;

    public HSFriendlyNameElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mNames = new ArrayList<I18Name>();

        while (payload.hasRemaining()) {
            mNames.add(new I18Name(payload));
        }
    }

    public List<I18Name> getNames() {
        return Collections.unmodifiableList(mNames);
    }

    @Override
    public String toString() {
        return "HSFriendlyNameElement{" +
                "mNames=" + mNames +
                '}';
    }
}
