package com.android.systemui.statusbar.policy;

import android.net.ConnectivityManager;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

public class NetworkControllerSignalTest extends NetworkControllerBaseTest {

    public void testSignalStrength() {
        int testStrength = SignalStrength.SIGNAL_STRENGTH_MODERATE;
        setIsGsm(true);
        setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        setGsmRoaming(false);
        setLevel(testStrength);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_UMTS);
        setConnectivity(100, ConnectivityManager.TYPE_MOBILE, true);

        verifyLastMobileDataIndicators(true,
                TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][testStrength]);
    }

    public void testSignalRoaming() {
        int testStrength = SignalStrength.SIGNAL_STRENGTH_MODERATE;
        setIsGsm(true);
        setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        setGsmRoaming(true);
        setLevel(testStrength);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_UMTS);
        setConnectivity(100, ConnectivityManager.TYPE_MOBILE, true);

        verifyLastMobileDataIndicators(true,
                TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][testStrength]);
    }
}
