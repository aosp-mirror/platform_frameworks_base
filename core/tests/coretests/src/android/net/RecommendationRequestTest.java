package android.net;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Parcel;
import android.test.AndroidTestCase;

public class RecommendationRequestTest extends AndroidTestCase {
    private ScanResult[] mScanResults;
    private WifiConfiguration mConfiguration;
    private NetworkCapabilities mCapabilities;

    @Override
    public void setUp() throws Exception {
        mScanResults = new ScanResult[2];
        mScanResults[0] = new ScanResult();
        mScanResults[1] = new ScanResult(
                "ssid",
                "bssid",
                0L /*hessid*/,
                1 /*anqpDominId*/,
                "caps",
                2 /*level*/,
                3 /*frequency*/,
                4L /*tsf*/,
                5 /*distCm*/,
                6 /*distSdCm*/,
                7 /*channelWidth*/,
                8 /*centerFreq0*/,
                9 /*centerFreq1*/,
                false /*is80211McRTTResponder*/);
        mConfiguration = new WifiConfiguration();
        mConfiguration.SSID = "RecommendationRequestTest";
        mCapabilities = new NetworkCapabilities()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
    }

    public void testParceling() throws Exception {
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setCurrentRecommendedWifiConfig(mConfiguration)
                .setScanResults(mScanResults)
                .setNetworkCapabilities(mCapabilities)
                .build();

        RecommendationRequest parceled = passThroughParcel(request);
        assertEquals(request.getCurrentSelectedConfig().SSID,
                parceled.getCurrentSelectedConfig().SSID);
        assertEquals(request.getRequiredCapabilities(), parceled.getRequiredCapabilities());
        ScanResult[] parceledScanResults = parceled.getScanResults();
        assertNotNull(parceledScanResults);
        assertEquals(mScanResults.length, parceledScanResults.length);
        for (int i = 0; i < mScanResults.length; i++) {
            assertEquals(mScanResults[i].SSID, parceledScanResults[i].SSID);
        }
    }

    public void testParceling_nullScanResults() throws Exception {
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setCurrentRecommendedWifiConfig(mConfiguration)
                .setNetworkCapabilities(mCapabilities)
                .build();

        RecommendationRequest parceled = passThroughParcel(request);
        assertEquals(request.getCurrentSelectedConfig().SSID,
                parceled.getCurrentSelectedConfig().SSID);
        assertEquals(request.getRequiredCapabilities(), parceled.getRequiredCapabilities());
        ScanResult[] parceledScanResults = parceled.getScanResults();
        assertNull(parceledScanResults);
    }

    private RecommendationRequest passThroughParcel(RecommendationRequest request) {
        Parcel p = Parcel.obtain();
        RecommendationRequest output = null;
        try {
            request.writeToParcel(p, 0);
            p.setDataPosition(0);
            output = RecommendationRequest.CREATOR.createFromParcel(p);
        } finally {
            p.recycle();
        }
        assertNotNull(output);
        return output;
    }
}
