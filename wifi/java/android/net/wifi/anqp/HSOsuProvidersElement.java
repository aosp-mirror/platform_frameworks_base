package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * The OSU Providers List vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8
 */
public class HSOsuProvidersElement extends ANQPElement {
    private final String mSSID;
    private final List<OSUProvider> mProviders;

    public HSOsuProvidersElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mSSID = Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8);
        int providerCount = payload.get() & BYTE_MASK;

        mProviders = new ArrayList<OSUProvider>(providerCount);

        while (providerCount > 0) {
            mProviders.add(new OSUProvider(payload));
            providerCount--;
        }
    }

    public String getSSID() {
        return mSSID;
    }

    public List<OSUProvider> getProviders() {
        return Collections.unmodifiableList(mProviders);
    }

    @Override
    public String toString() {
        return "HSOsuProvidersElement{" +
                "mSSID='" + mSSID + '\'' +
                ", mProviders=" + mProviders +
                '}';
    }
}
