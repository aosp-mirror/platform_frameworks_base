package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * ANQP related constants (802.11-2012)
 */
public class Constants {

    public static final int BYTE_MASK = 0xff;
    public static final int SHORT_MASK = 0xffff;
    public static final long INT_MASK = 0xffffffffL;
    public static final int BYTES_IN_SHORT = 2;
    public static final int BYTES_IN_INT = 4;

    public static final int HS20_PREFIX = 0x119a6f50;   // Note that is represented as a LE int
    public static final int UTF8_INDICATOR = 1;

    public enum IconStatus {Success, FileNotFound, Unspecified}

    public static final int ANQP_QUERY_LIST = 256;
    public static final int ANQP_CAPABILITY_LIST = 257;
    public static final int ANQP_VENUE_NAME = 258;
    public static final int ANQP_EMERGENCY_NUMBER = 259;
    public static final int ANQP_NWK_AUTH_TYPE = 260;
    public static final int ANQP_ROAMING_CONSORTIUM = 261;
    public static final int ANQP_IP_ADDR_AVAILABILITY = 262;
    public static final int ANQP_NAI_REALM = 263;
    public static final int ANQP_3GPP_NETWORK = 264;
    public static final int ANQP_GEO_LOC = 265;
    public static final int ANQP_CIVIC_LOC = 266;
    public static final int ANQP_LOC_URI = 267;
    public static final int ANQP_DOM_NAME = 268;
    public static final int ANQP_EMERGENCY_ALERT = 269;
    public static final int ANQP_TDLS_CAP = 270;
    public static final int ANQP_EMERGENCY_NAI = 271;
    public static final int ANQP_NEIGHBOR_REPORT = 272;
    public static final int ANQP_VENDOR_SPEC = 56797;

    public static final int HS_QUERY_LIST = 1;
    public static final int HS_CAPABILITY_LIST = 2;
    public static final int HS_FRIENDLY_NAME = 3;
    public static final int HS_WAN_METRICS = 4;
    public static final int HS_CONN_CAPABILITY = 5;
    public static final int HS_NAI_HOME_REALM_QUERY = 6;
    public static final int HS_OPERATING_CLASS = 7;
    public static final int HS_OSU_PROVIDERS = 8;
    public static final int HS_ICON_REQUEST = 10;
    public static final int HS_ICON_FILE = 11;

    public enum ANQPElementType {
        ANQPQueryList,
        ANQPCapabilityList,
        ANQPVenueName,
        ANQPEmergencyNumber,
        ANQPNwkAuthType,
        ANQPRoamingConsortium,
        ANQPIPAddrAvailability,
        ANQPNAIRealm,
        ANQP3GPPNetwork,
        ANQPGeoLoc,
        ANQPCivicLoc,
        ANQPLocURI,
        ANQPDomName,
        ANQPEmergencyAlert,
        ANQPTDLSCap,
        ANQPEmergencyNAI,
        ANQPNeighborReport,
        ANQPVendorSpec,
        HSQueryList,
        HSCapabilityList,
        HSFriendlyName,
        HSWANMetrics,
        HSConnCapability,
        HSNAIHomeRealmQuery,
        HSOperatingclass,
        HSOSUProviders,
        HSIconRequest,
        HSIconFile
    }

    private static final Map<Integer, ANQPElementType> sAnqpMap = new HashMap<Integer, ANQPElementType>();
    private static final Map<Integer, ANQPElementType> sHs20Map = new HashMap<Integer, ANQPElementType>();
    private static final Map<ANQPElementType, Integer> sRevAnqpmap = new HashMap<ANQPElementType, Integer>();
    private static final Map<ANQPElementType, Integer> sRevHs20map = new HashMap<ANQPElementType, Integer>();

    static {
        sAnqpMap.put(ANQP_QUERY_LIST, ANQPElementType.ANQPQueryList);
        sAnqpMap.put(ANQP_CAPABILITY_LIST, ANQPElementType.ANQPCapabilityList);
        sAnqpMap.put(ANQP_VENUE_NAME, ANQPElementType.ANQPVenueName);
        sAnqpMap.put(ANQP_EMERGENCY_NUMBER, ANQPElementType.ANQPEmergencyNumber);
        sAnqpMap.put(ANQP_NWK_AUTH_TYPE, ANQPElementType.ANQPNwkAuthType);
        sAnqpMap.put(ANQP_ROAMING_CONSORTIUM, ANQPElementType.ANQPRoamingConsortium);
        sAnqpMap.put(ANQP_IP_ADDR_AVAILABILITY, ANQPElementType.ANQPIPAddrAvailability);
        sAnqpMap.put(ANQP_NAI_REALM, ANQPElementType.ANQPNAIRealm);
        sAnqpMap.put(ANQP_3GPP_NETWORK, ANQPElementType.ANQP3GPPNetwork);
        sAnqpMap.put(ANQP_GEO_LOC, ANQPElementType.ANQPGeoLoc);
        sAnqpMap.put(ANQP_CIVIC_LOC, ANQPElementType.ANQPCivicLoc);
        sAnqpMap.put(ANQP_LOC_URI, ANQPElementType.ANQPLocURI);
        sAnqpMap.put(ANQP_DOM_NAME, ANQPElementType.ANQPDomName);
        sAnqpMap.put(ANQP_EMERGENCY_ALERT, ANQPElementType.ANQPEmergencyAlert);
        sAnqpMap.put(ANQP_TDLS_CAP, ANQPElementType.ANQPTDLSCap);
        sAnqpMap.put(ANQP_EMERGENCY_NAI, ANQPElementType.ANQPEmergencyNAI);
        sAnqpMap.put(ANQP_NEIGHBOR_REPORT, ANQPElementType.ANQPNeighborReport);
        sAnqpMap.put(ANQP_VENDOR_SPEC, ANQPElementType.ANQPVendorSpec);

        sHs20Map.put(HS_QUERY_LIST, ANQPElementType.HSQueryList);
        sHs20Map.put(HS_CAPABILITY_LIST, ANQPElementType.HSCapabilityList);
        sHs20Map.put(HS_FRIENDLY_NAME, ANQPElementType.HSFriendlyName);
        sHs20Map.put(HS_WAN_METRICS, ANQPElementType.HSWANMetrics);
        sHs20Map.put(HS_CONN_CAPABILITY, ANQPElementType.HSConnCapability);
        sHs20Map.put(HS_NAI_HOME_REALM_QUERY, ANQPElementType.HSNAIHomeRealmQuery);
        sHs20Map.put(HS_OPERATING_CLASS, ANQPElementType.HSOperatingclass);
        sHs20Map.put(HS_OSU_PROVIDERS, ANQPElementType.HSOSUProviders);
        sHs20Map.put(HS_ICON_REQUEST, ANQPElementType.HSIconRequest);
        sHs20Map.put(HS_ICON_FILE, ANQPElementType.HSIconFile);

        for (Map.Entry<Integer, ANQPElementType> entry : sAnqpMap.entrySet()) {
            sRevAnqpmap.put(entry.getValue(), entry.getKey());
        }
        for (Map.Entry<Integer, ANQPElementType> entry : sHs20Map.entrySet()) {
            sRevHs20map.put(entry.getValue(), entry.getKey());
        }
    }

    public static ANQPElementType mapANQPElement(int id) {
        return sAnqpMap.get(id);
    }

    public static ANQPElementType mapHS20Element(int id) {
        return sHs20Map.get(id);
    }

    public static Integer getANQPElementID(ANQPElementType elementType) {
        return sRevAnqpmap.get(elementType);
    }

    public static Integer getHS20ElementID(ANQPElementType elementType) {
        return sRevHs20map.get(elementType);
    }

    public static long getInteger(ByteBuffer payload, int size) {
        byte[] octets = new byte[size];
        payload.get(octets);
        long value = 0;
        for (int n = octets.length - 1; n >= 0; n--) {
            value = (value << Byte.SIZE) | (octets[n] & BYTE_MASK);
        }
        return value;
    }

    public static String getPrefixedString(ByteBuffer payload, int lengthLength, Charset charset)
            throws ProtocolException {
        if (payload.remaining() < lengthLength) {
            throw new ProtocolException("Runt string: " + payload.remaining());
        }
        return getString(payload, (int) getInteger(payload, lengthLength), charset, false);
    }

    public static String getString(ByteBuffer payload, int length, Charset charset)
            throws ProtocolException {
        return getString(payload, length, charset, false);
    }

    public static String getString(ByteBuffer payload, int length, Charset charset, boolean useNull)
            throws ProtocolException {
        if (length > payload.remaining()) {
            throw new ProtocolException("Bad string length: " + length);
        }
        if (useNull && length == 0) {
            return null;
        }
        byte[] octets = new byte[length];
        return new String(octets, charset);
    }

    public static String toHexString(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);

        boolean first = true;
        for (byte b : data) {
            if (first) {
                first = false;
            } else {
                sb.append(' ');
            }
            sb.append(String.format("%02x", b & BYTE_MASK));
        }
        return sb.toString();
    }
}
