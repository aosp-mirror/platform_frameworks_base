package android.net.wifi.hotspot2;

import android.net.wifi.anqp.VenueNameElement;

/**
 * Created by jannq on 1/20/15.
 */
public class NetworkInfo {

    public enum Ant {
        Private,
        PrivateWithGuest,
        ChargeablePublic,
        FreePublic,
        Personal,
        EmergencyOnly,
        TestOrExperimental,
        Wildcard
    }

    public enum HSRelease {
        R1,
        R2,
        Unknown
    }

    // General identifiers:
    private final String mSSID;
    private final String mHESSID;
    private final long mBSSID;

    // BSS Load element:
    private final int mStationCount;
    private final int mChannelUtilization;
    private final int mCapacity;

    /*
     * From Interworking element:
     * mAnt non null indicates the presence of Interworking, i.e. 802.11u
     * mVenueGroup and mVenueType may be null if not present in the Interworking element.
     */
    private final Ant mAnt;
    private final boolean mInternet;
    private final VenueNameElement.VenueGroup mVenueGroup;
    private final VenueNameElement.VenueType mVenueType;

    /*
     * From HS20 Indication element:
     * mHSRelease is null only if the HS20 Indication element was not present.
     * mAnqpDomainID is set to -1 if not present in the element.
     */
    private final HSRelease mHSRelease;
    private final int mAnqpDomainID;

    /*
     * From beacon:
     * mRoamingConsortiums is either null, if the element was not present, or is an array of
     * 1, 2 or 3 longs in which the roaming consortium values occupy the LSBs.
     */
    private final long[] mRoamingConsortiums;

    public NetworkInfo(String SSID,
                       String HESSID,
                       long BSSID,
                       int stationCount,
                       int channelUtilization,
                       int capacity,
                       Ant ant,
                       boolean internet,
                       VenueNameElement.VenueGroup venueGroup,
                       VenueNameElement.VenueType venueType,
                       HSRelease HSRelease,
                       int anqpDomainID,
                       long[] roamingConsortiums) {
        mSSID = SSID;
        mHESSID = HESSID;
        mBSSID = BSSID;
        mStationCount = stationCount;
        mChannelUtilization = channelUtilization;
        mCapacity = capacity;
        mAnt = ant;
        mInternet = internet;
        mVenueGroup = venueGroup;
        mVenueType = venueType;
        mHSRelease = HSRelease;
        mAnqpDomainID = anqpDomainID;
        mRoamingConsortiums = roamingConsortiums;
    }

    public String getSSID() {
        return mSSID;
    }

    public String getHESSID() {
        return mHESSID;
    }

    public long getBSSID() {
        return mBSSID;
    }

    public int getStationCount() {
        return mStationCount;
    }

    public int getChannelUtilization() {
        return mChannelUtilization;
    }

    public int getCapacity() {
        return mCapacity;
    }

    public Ant getAnt() {
        return mAnt;
    }

    public boolean isInternet() {
        return mInternet;
    }

    public VenueNameElement.VenueGroup getVenueGroup() {
        return mVenueGroup;
    }

    public VenueNameElement.VenueType getVenueType() {
        return mVenueType;
    }

    public HSRelease getHSRelease() {
        return mHSRelease;
    }

    public int getAnqpDomainID() {
        return mAnqpDomainID;
    }

    public long[] getRoamingConsortiums() {
        return mRoamingConsortiums;
    }
}
