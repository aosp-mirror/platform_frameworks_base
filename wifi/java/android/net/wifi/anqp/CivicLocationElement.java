package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * The Civic Location ANQP Element, IEEE802.11-2012 section 8.4.4.13
 */
public class CivicLocationElement extends ANQPElement {
    public enum LocationType {DHCPServer, NwkElement, Client}

    private static final int GEOCONF_CIVIC4 = 99;
    private static final int RFC4776 = 0;       // Table 8-77, 1=vendor specific

    private final LocationType mLocationType;
    private final Locale mLocale;
    private final Map<CAType, String> mValues;

    public CivicLocationElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        if (payload.remaining() < 6) {
            throw new ProtocolException("Runt civic location:" + payload.remaining());
        }

        int locType = payload.get() & BYTE_MASK;
        if (locType != RFC4776) {
            throw new ProtocolException("Bad Civic location type: " + locType);
        }

        int locSubType = payload.get() & BYTE_MASK;
        if (locSubType != GEOCONF_CIVIC4) {
            throw new ProtocolException("Unexpected Civic location sub-type: " + locSubType +
                    " (cannot handle sub elements)");
        }

        int length = payload.get() & BYTE_MASK;
        if (length > payload.remaining()) {
            throw new ProtocolException("Invalid CA type length: " + length);
        }

        int what = payload.get() & BYTE_MASK;
        mLocationType = what < LocationType.values().length ? LocationType.values()[what] : null;

        mLocale = Locale.forLanguageTag(Constants.getString(payload, 2, StandardCharsets.US_ASCII));

        mValues = new HashMap<CAType, String>();
        while (payload.hasRemaining()) {
            int caTypeNumber = payload.get() & BYTE_MASK;
            CAType caType = s_caTypes.get(caTypeNumber);

            int caValLen = payload.get() & BYTE_MASK;
            if (caValLen > payload.remaining()) {
                throw new ProtocolException("Bad CA value length: " + caValLen);
            }
            byte[] caValOctets = new byte[caValLen];
            payload.get(caValOctets);

            if (caType != null) {
                mValues.put(caType, new String(caValOctets, StandardCharsets.UTF_8));
            }
        }
    }

    public LocationType getLocationType() {
        return mLocationType;
    }

    public Locale getLocale() {
        return mLocale;
    }

    public Map<CAType, String> getValues() {
        return Collections.unmodifiableMap(mValues);
    }

    @Override
    public String toString() {
        return "CivicLocationElement{" +
                "mLocationType=" + mLocationType +
                ", mLocale=" + mLocale +
                ", mValues=" + mValues +
                '}';
    }

    private static final Map<Integer, CAType> s_caTypes = new HashMap<Integer, CAType>();

    public static final int LANGUAGE = 0;
    public static final int STATE_PROVINCE = 1;
    public static final int COUNTY_DISTRICT = 2;
    public static final int CITY = 3;
    public static final int DIVISION_BOROUGH = 4;
    public static final int BLOCK = 5;
    public static final int STREET_GROUP = 6;
    public static final int STREET_DIRECTION = 16;
    public static final int LEADING_STREET_SUFFIX = 17;
    public static final int STREET_SUFFIX = 18;
    public static final int HOUSE_NUMBER = 19;
    public static final int HOUSE_NUMBER_SUFFIX = 20;
    public static final int LANDMARK = 21;
    public static final int ADDITIONAL_LOCATION = 22;
    public static final int NAME = 23;
    public static final int POSTAL_ZIP = 24;
    public static final int BUILDING = 25;
    public static final int UNIT = 26;
    public static final int FLOOR = 27;
    public static final int ROOM = 28;
    public static final int TYPE = 29;
    public static final int POSTAL_COMMUNITY = 30;
    public static final int PO_BOX = 31;
    public static final int ADDITIONAL_CODE = 32;
    public static final int SEAT_DESK = 33;
    public static final int PRIMARY_ROAD = 34;
    public static final int ROAD_SECTION = 35;
    public static final int BRANCH_ROAD = 36;
    public static final int SUB_BRANCH_ROAD = 37;
    public static final int STREET_NAME_PRE_MOD = 38;
    public static final int STREET_NAME_POST_MOD = 39;
    public static final int SCRIPT = 128;
    public static final int RESERVED = 255;

    public enum CAType {
        Language,
        StateProvince,
        CountyDistrict,
        City,
        DivisionBorough,
        Block,
        StreetGroup,
        StreetDirection,
        LeadingStreetSuffix,
        StreetSuffix,
        HouseNumber,
        HouseNumberSuffix,
        Landmark,
        AdditionalLocation,
        Name,
        PostalZIP,
        Building,
        Unit,
        Floor,
        Room,
        Type,
        PostalCommunity,
        POBox,
        AdditionalCode,
        SeatDesk,
        PrimaryRoad,
        RoadSection,
        BranchRoad,
        SubBranchRoad,
        StreetNamePreMod,
        StreetNamePostMod,
        Script,
        Reserved
    }

    static {
        s_caTypes.put(LANGUAGE, CAType.Language);
        s_caTypes.put(STATE_PROVINCE, CAType.StateProvince);
        s_caTypes.put(COUNTY_DISTRICT, CAType.CountyDistrict);
        s_caTypes.put(CITY, CAType.City);
        s_caTypes.put(DIVISION_BOROUGH, CAType.DivisionBorough);
        s_caTypes.put(BLOCK, CAType.Block);
        s_caTypes.put(STREET_GROUP, CAType.StreetGroup);
        s_caTypes.put(STREET_DIRECTION, CAType.StreetDirection);
        s_caTypes.put(LEADING_STREET_SUFFIX, CAType.LeadingStreetSuffix);
        s_caTypes.put(STREET_SUFFIX, CAType.StreetSuffix);
        s_caTypes.put(HOUSE_NUMBER, CAType.HouseNumber);
        s_caTypes.put(HOUSE_NUMBER_SUFFIX, CAType.HouseNumberSuffix);
        s_caTypes.put(LANDMARK, CAType.Landmark);
        s_caTypes.put(ADDITIONAL_LOCATION, CAType.AdditionalLocation);
        s_caTypes.put(NAME, CAType.Name);
        s_caTypes.put(POSTAL_ZIP, CAType.PostalZIP);
        s_caTypes.put(BUILDING, CAType.Building);
        s_caTypes.put(UNIT, CAType.Unit);
        s_caTypes.put(FLOOR, CAType.Floor);
        s_caTypes.put(ROOM, CAType.Room);
        s_caTypes.put(TYPE, CAType.Type);
        s_caTypes.put(POSTAL_COMMUNITY, CAType.PostalCommunity);
        s_caTypes.put(PO_BOX, CAType.POBox);
        s_caTypes.put(ADDITIONAL_CODE, CAType.AdditionalCode);
        s_caTypes.put(SEAT_DESK, CAType.SeatDesk);
        s_caTypes.put(PRIMARY_ROAD, CAType.PrimaryRoad);
        s_caTypes.put(ROAD_SECTION, CAType.RoadSection);
        s_caTypes.put(BRANCH_ROAD, CAType.BranchRoad);
        s_caTypes.put(SUB_BRANCH_ROAD, CAType.SubBranchRoad);
        s_caTypes.put(STREET_NAME_PRE_MOD, CAType.StreetNamePreMod);
        s_caTypes.put(STREET_NAME_POST_MOD, CAType.StreetNamePostMod);
        s_caTypes.put(SCRIPT, CAType.Script);
        s_caTypes.put(RESERVED, CAType.Reserved);
    }
}
