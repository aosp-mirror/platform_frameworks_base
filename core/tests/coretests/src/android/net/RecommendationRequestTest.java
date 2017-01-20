package android.net;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Parcel;
import android.test.AndroidTestCase;

public class RecommendationRequestTest extends AndroidTestCase {
    private ScanResult[] mScanResults;
    private WifiConfiguration mDefaultConfig;
    private WifiConfiguration mConnectedConfig;
    private WifiConfiguration[] mConnectableConfigs;

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
        mDefaultConfig = new WifiConfiguration();
        mDefaultConfig.SSID = "default_config";
        mConnectedConfig = new WifiConfiguration();
        mConnectedConfig.SSID = "connected_config";
        mConnectableConfigs = new WifiConfiguration[] {mDefaultConfig, mConnectedConfig};
    }

    public void testParceling() throws Exception {
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setDefaultWifiConfig(mDefaultConfig)
                .setScanResults(mScanResults)
                .setConnectedWifiConfig(mConnectedConfig)
                .setConnectableConfigs(mConnectableConfigs)
                .build();

        RecommendationRequest parceled = passThroughParcel(request);
        assertEquals(request.getDefaultWifiConfig().SSID,
                parceled.getDefaultWifiConfig().SSID);
        assertEquals(request.getConnectedConfig().SSID,
                parceled.getConnectedConfig().SSID);
        ScanResult[] parceledScanResults = parceled.getScanResults();
        assertNotNull(parceledScanResults);
        assertEquals(mScanResults.length, parceledScanResults.length);
        for (int i = 0; i < mScanResults.length; i++) {
            assertEquals(mScanResults[i].SSID, parceledScanResults[i].SSID);
        }
        WifiConfiguration[] parceledConfigs = parceled.getConnectableConfigs();
        for (int i = 0; i < parceledConfigs.length; i++) {
            assertEquals(mConnectableConfigs[i].SSID, parceledConfigs[i].SSID);
        }
    }

    public void testParceling_nullScanResults() throws Exception {
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setDefaultWifiConfig(mDefaultConfig)
                .build();

        RecommendationRequest parceled = passThroughParcel(request);
        ScanResult[] parceledScanResults = parceled.getScanResults();
        assertNull(parceledScanResults);
    }

    public void testParceling_nullWifiConfigArray() throws Exception {
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setDefaultWifiConfig(mDefaultConfig)
                .build();

        RecommendationRequest parceled = passThroughParcel(request);
        WifiConfiguration[] parceledConfigs = parceled.getConnectableConfigs();
        assertNull(parceledConfigs);
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
