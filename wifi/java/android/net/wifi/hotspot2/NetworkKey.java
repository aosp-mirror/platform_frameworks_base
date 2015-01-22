package android.net.wifi.hotspot2;

/**
 * Created by jannq on 1/20/15.
 */
public class NetworkKey {
    private final String mSSID;
    private final long mBSSID;
    private final int mANQPDomainID;

    public NetworkKey(String SSID, long BSSID, int ANQPDomainID) {
        mSSID = SSID;
        mBSSID = BSSID;
        mANQPDomainID = ANQPDomainID;
    }

    public String getSSID() {
        return mSSID;
    }

    public long getBSSID() {
        return mBSSID;
    }

    public int getANQPDomainID() {
        return mANQPDomainID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NetworkKey that = (NetworkKey) o;

        if (mANQPDomainID != that.mANQPDomainID) return false;
        if (mBSSID != that.mBSSID) return false;
        if (!mSSID.equals(that.mSSID)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = mSSID.hashCode();
        result = 31 * result + (int) (mBSSID ^ (mBSSID >>> 32));
        result = 31 * result + mANQPDomainID;
        return result;
    }
}
