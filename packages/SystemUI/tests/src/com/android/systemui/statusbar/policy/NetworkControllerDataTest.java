package com.android.systemui.statusbar.policy;

import org.mockito.Mockito;

import android.telephony.TelephonyManager;

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
                TelephonyIcons.QS_DATA_R[1], false, false);
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

        verifyDataIndicators(TelephonyIcons.DATA_LTE[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_LTE[1]);
    }

    public void testHspaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPA);

        verifyDataIndicators(TelephonyIcons.DATA_H[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_H[1]);
    }

    public void test4gDataIcon() {
        // Switch to showing 4g icon and re-initialize the NetworkController.
        mConfig.show4gForLte = true;
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, Mockito.mock(AccessPointControllerImpl.class),
                Mockito.mock(MobileDataControllerImpl.class));
        setupNetworkController();

        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.DATA_4G[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_4G[1]);
    }

    public void test4gDataIconConfigChange() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        // Switch to showing 4g icon and re-initialize the NetworkController.
        mConfig.show4gForLte = true;
        // Can't send the broadcast as that would actually read the config from
        // the context.  Instead we'll just poke at a function that does all of
        // the after work.
        mNetworkController.handleConfigurationChanged();

        verifyDataIndicators(TelephonyIcons.DATA_4G[1][0 /* No direction */],
                TelephonyIcons.QS_DATA_4G[1]);
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
                DEFAULT_QS_ICON, in, out);

    }

    private void verifyDataIndicators(int dataIcon, int qsDataIcon) {
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, dataIcon);
        verifyLastQsMobileDataIndicators(true, DEFAULT_QS_SIGNAL_STRENGTH, qsDataIcon, false,
                false);
    }

}
