package android.net.wifi.anqp;

import android.net.wifi.anqp.eap.EAPMethod;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.net.wifi.anqp.Constants.BYTE_MASK;
import static android.net.wifi.anqp.Constants.SHORT_MASK;
import static android.net.wifi.anqp.Constants.UTF8_INDICATOR;

/**
 * The NAI Realm Data ANQP sub-element, IEEE802.11-2012 section 8.4.4.10 figure 8-418
 */
public class NAIRealmData {
    private final List<String> mRealms;
    private final List<EAPMethod> mEAPMethods;

    public NAIRealmData(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 5) {
            throw new ProtocolException("Runt payload: " + payload.remaining());
        }

        int length = payload.getShort() & SHORT_MASK;
        if (length > payload.remaining()) {
            throw new ProtocolException("Invalid data length: " + length);
        }
        boolean utf8 = (payload.get() & 1) == UTF8_INDICATOR;

        String realm = Constants.getPrefixedString(payload, 1, utf8 ?
                StandardCharsets.UTF_8 :
                StandardCharsets.US_ASCII);
        String[] realms = realm.split(";");
        mRealms = new ArrayList<String>();
        for (String realmElement : realms) {
            if (realmElement.length() > 0) {
                mRealms.add(realmElement);
            }
        }

        int methodCount = payload.get() & BYTE_MASK;
        mEAPMethods = new ArrayList<EAPMethod>(methodCount);
        while (methodCount > 0) {
            mEAPMethods.add(new EAPMethod(payload));
            methodCount--;
        }
    }

    public List<String> getRealms() {
        return Collections.unmodifiableList(mRealms);
    }

    public List<EAPMethod> getEAPMethods() {
        return Collections.unmodifiableList(mEAPMethods);
    }

    @Override
    public String toString() {
        return "NAIRealmData{" +
                "mRealms='" + mRealms + '\'' +
                ", mEAPMethods=" + mEAPMethods +
                '}';
    }
}
