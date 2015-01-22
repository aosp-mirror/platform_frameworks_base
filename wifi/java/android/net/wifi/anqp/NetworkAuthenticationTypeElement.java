package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * The Network Authentication Type ANQP Element, IEEE802.11-2012 section 8.4.4.6
 */
public class NetworkAuthenticationTypeElement extends ANQPElement {

    private final List<NetworkAuthentication> m_authenticationTypes;

    public enum NwkAuthTypeEnum {
        TermsAndConditions,
        OnLineEnrollment,
        HTTPRedirection,
        DNSRedirection,
        Reserved
    }

    public static class NetworkAuthentication {
        private final NwkAuthTypeEnum m_type;
        private final String m_url;

        private NetworkAuthentication(NwkAuthTypeEnum type, String url) {
            m_type = type;
            m_url = url;
        }

        public NwkAuthTypeEnum getType() {
            return m_type;
        }

        public String getURL() {
            return m_url;
        }

        @Override
        public String toString() {
            return "NetworkAuthentication{" +
                    "m_type=" + m_type +
                    ", m_url='" + m_url + '\'' +
                    '}';
        }
    }

    public NetworkAuthenticationTypeElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {

        super(infoID);

        m_authenticationTypes = new ArrayList<NetworkAuthentication>();

        while (payload.hasRemaining()) {
            int typeNumber = payload.get() & BYTE_MASK;
            NwkAuthTypeEnum type;
            type = typeNumber >= NwkAuthTypeEnum.values().length ?
                    NwkAuthTypeEnum.Reserved :
                    NwkAuthTypeEnum.values()[typeNumber];

            m_authenticationTypes.add(new NetworkAuthentication(type,
                    Constants.getPrefixedString(payload, 2, StandardCharsets.UTF_8)));
        }
    }

    public List<NetworkAuthentication> getAuthenticationTypes() {
        return Collections.unmodifiableList(m_authenticationTypes);
    }
}
