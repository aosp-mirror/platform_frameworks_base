package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import static android.net.wifi.anqp.Constants.*;

/**
 * Factory to build a collection of 802.11u ANQP elements from a byte buffer.
 */
public class ANQPFactory {

    public static ByteBuffer buildQueryRequest(Set<ANQPElementType> elements, ByteBuffer target) {
        List<ANQPElementType> list = new ArrayList<ANQPElementType>(elements);
        Collections.sort(list);

        ListIterator<ANQPElementType> elementIterator = list.listIterator();

        target.order(ByteOrder.LITTLE_ENDIAN);
        target.putShort((short) Constants.ANQP_QUERY_LIST);
        int lenPos = target.position();
        target.putShort((short) 0);

        while (elementIterator.hasNext()) {
            Integer id = Constants.getANQPElementID(elementIterator.next());
            if (id != null) {
                target.putShort(id.shortValue());
            } else {
                elementIterator.previous();
            }
        }
        target.putShort(lenPos, (short) (target.position() - lenPos - BYTES_IN_SHORT));

        // Start a new vendor specific element for HS2.0 elements:
        if (elementIterator.hasNext()) {
            target.putShort((short) ANQP_VENDOR_SPEC);
            int vsLenPos = target.position();
            target.putShort((short) 0);

            target.putInt(Constants.HS20_PREFIX);
            target.put((byte) Constants.HS_QUERY_LIST);
            target.put((byte) 0);

            while (elementIterator.hasNext()) {
                ANQPElementType elementType = elementIterator.next();
                Integer id = Constants.getHS20ElementID(elementType);
                if (id == null) {
                    throw new RuntimeException("Unmapped ANQPElementType: " + elementType);
                } else {
                    target.put(id.byteValue());
                }
            }
            target.putShort(vsLenPos, (short) (target.position() - vsLenPos - BYTES_IN_SHORT));
        }

        target.flip();
        return target;
    }

    public static ByteBuffer buildHomeRealmRequest(List<String> realmNames, ByteBuffer target) {
        target.order(ByteOrder.LITTLE_ENDIAN);
        target.putShort((short) ANQP_VENDOR_SPEC);
        int lenPos = target.position();
        target.putShort((short) 0);

        target.putInt(Constants.HS20_PREFIX);
        target.put((byte) Constants.HS_NAI_HOME_REALM_QUERY);
        target.put((byte) 0);

        target.put((byte) realmNames.size());
        for (String realmName : realmNames) {
            target.put((byte) UTF8_INDICATOR);
            byte[] octets = realmName.getBytes(StandardCharsets.UTF_8);
            target.put((byte) octets.length);
            target.put(octets);
        }
        target.putShort(lenPos, (short) (target.position() - lenPos - BYTES_IN_SHORT));

        target.flip();
        return target;
    }

    public static ByteBuffer buildIconRequest(String fileName, ByteBuffer target) {
        target.order(ByteOrder.LITTLE_ENDIAN);
        target.putShort((short) ANQP_VENDOR_SPEC);
        int lenPos = target.position();
        target.putShort((short) 0);

        target.putInt(Constants.HS20_PREFIX);
        target.put((byte) Constants.HS_ICON_REQUEST);
        target.put((byte) 0);

        target.put(fileName.getBytes(StandardCharsets.UTF_8));
        target.putShort(lenPos, (short) (target.position() - lenPos - BYTES_IN_SHORT));

        target.flip();
        return target;
    }

    public static List<ANQPElement> parsePayload(ByteBuffer payload) throws ProtocolException {
        payload.order(ByteOrder.LITTLE_ENDIAN);
        List<ANQPElement> elements = new ArrayList<ANQPElement>();
        while (payload.hasRemaining()) {
            elements.add(buildElement(payload));
        }
        return elements;
    }

    private static ANQPElement buildElement(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 4)
            throw new ProtocolException("Runt payload: " + payload.remaining());

        int infoIDNumber = payload.getShort() & SHORT_MASK;
        ANQPElementType infoID = Constants.mapANQPElement(infoIDNumber);
        if (infoID == null) {
            throw new ProtocolException("Bad info ID: " + infoIDNumber);
        }
        int length = payload.getShort() & SHORT_MASK;

        if (payload.remaining() < length) {
            throw new ProtocolException("Truncated payload");
        }

        ByteBuffer elementPayload = payload.duplicate();
        payload.position(payload.position() + length);
        elementPayload.limit(elementPayload.position() + length);

        switch (infoID) {
            case ANQPCapabilityList:
                return new CapabilityListElement(infoID, elementPayload);
            case ANQPVenueName:
                return new VenueNameElement(infoID, elementPayload);
            case ANQPEmergencyNumber:
                return new EmergencyNumberElement(infoID, elementPayload);
            case ANQPNwkAuthType:
                return new NetworkAuthenticationTypeElement(infoID, elementPayload);
            case ANQPRoamingConsortium:
                return new RoamingConsortiumElement(infoID, elementPayload);
            case ANQPIPAddrAvailability:
                return new IPAddressTypeAvailabilityElement(infoID, elementPayload);
            case ANQPNAIRealm:
                return new NAIRealmElement(infoID, elementPayload);
            case ANQP3GPPNetwork:
                return new ThreeGPPNetworkElement(infoID, elementPayload);
            case ANQPGeoLoc:
                return new GEOLocationElement(infoID, elementPayload);
            case ANQPCivicLoc:
                return new CivicLocationElement(infoID, elementPayload);
            case ANQPLocURI:
                return new GenericStringElement(infoID, elementPayload);
            case ANQPDomName:
                return new DomainNameElement(infoID, elementPayload);
            case ANQPEmergencyAlert:
                return new GenericStringElement(infoID, elementPayload);
            case ANQPTDLSCap:
                return new GenericBlobElement(infoID, elementPayload);
            case ANQPEmergencyNAI:
                return new GenericStringElement(infoID, elementPayload);
            case ANQPNeighborReport:
                return new GenericBlobElement(infoID, elementPayload);
            case ANQPVendorSpec:
                if (elementPayload.remaining() > 5) {
                    int oi = elementPayload.getInt();
                    if (oi != Constants.HS20_PREFIX) {
                        return null;
                    }
                    int subType = elementPayload.get() & BYTE_MASK;
                    elementPayload.get();
                    return buildHS20Element(subType, elementPayload);
                } else {
                    return new GenericBlobElement(infoID, elementPayload);
                }
            default:
                throw new ProtocolException("Unknown element ID: " + infoID);
        }
    }

    private static ANQPElement buildHS20Element(int subType, ByteBuffer payload)
            throws ProtocolException {

        ANQPElementType infoID = Constants.mapHS20Element(subType);

        if (infoID == null) {
            throw new ProtocolException("Bad HS20 info ID: " + subType);
        }

        switch (infoID) {
            case HSCapabilityList:
                return new HSCapabilityListElement(infoID, payload);
            case HSFriendlyName:
                return new HSFriendlyNameElement(infoID, payload);
            case HSWANMetrics:
                return new HSWanMetricsElement(infoID, payload);
            case HSConnCapability:
                return new HSConnectionCapabilityElement(infoID, payload);
            case HSOperatingclass:
                return new GenericBlobElement(infoID, payload);
            case HSOSUProviders:
                return new HSOsuProvidersElement(infoID, payload);
            case HSIconFile:
                return new HSIconFileElement(infoID, payload);
            default:
                throw new ProtocolException("Unknown HS20 sub type: " + subType);
        }
    }
}
