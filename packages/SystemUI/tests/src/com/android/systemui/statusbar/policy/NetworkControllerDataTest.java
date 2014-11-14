package com.android.systemui.statusbar.policy;

import android.telephony.TelephonyManager;

// WARNING: Many of these tests may fail with config showMin3G.
// TODO: Maybe fix the above.
public class NetworkControllerDataTest extends NetworkControllerBaseTest {

    public void test3gDataIcon() {
        setupDefaultSignal();

        verifyDataIndicators(TelephonyIcons.DATA_3G[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_3G[1]);
    }

    public void testRoamingDataIcon() {
        setupDefaultSignal();
        setGsmRoaming(true);

        verifyLastMobileDataIndicators(true,
                TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][DEFAULT_LEVEL],
                TelephonyIcons.ROAMING_ICON);
        verifyLastQsMobileDataIndicators(true,
                TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL],
                TelephonyIcons.QS_DATA_R[1], false, false, false);
    }

    public void test2gDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_GSM);

        verifyDataIndicators(TelephonyIcons.DATA_G[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_G[1]);
    }

    public void testCdmaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);

        verifyDataIndicators(TelephonyIcons.DATA_1X[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_1X[1]);
    }

    public void testEdgeDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_EDGE);

        verifyDataIndicators(TelephonyIcons.DATA_E[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_E[1]);
    }

    public void testLteDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        // WARNING: May fail depending on config.
        verifyDataIndicators(TelephonyIcons.DATA_LTE[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_LTE[1]);
    }

    public void testHspaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPA);

        // WARNING: May fail depending on config.
        verifyDataIndicators(TelephonyIcons.DATA_H[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_H[1]);
    }

    public void testDataActivity() {
        setupDefaultSignal();

        testDataActivity(TelephonyManager.DATA_ACTIVITY_NONE, false, false);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_IN, true, false);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_OUT, false, true);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_INOUT, true, true);
    }

    private void testDataActivity(int direction, boolean in, boolean out) {
        updateDataActivity(direction);

        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, DEFAULT_ICON);
        verifyLastQsMobileDataIndicators(true, DEFAULT_QS_SIGNAL_STRENGTH,
                DEFAULT_QS_ICON, in, out, false);

    }

    private void verifyDataIndicators(int dataIcon, int qsDataIcon) {
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, dataIcon);
        verifyLastQsMobileDataIndicators(true, DEFAULT_QS_SIGNAL_STRENGTH, qsDataIcon, false,
                false, false);
    }

}
