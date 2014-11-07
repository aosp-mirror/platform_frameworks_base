package com.android.systemui.statusbar.policy;

import android.net.ConnectivityManager;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import com.android.systemui.R;

import org.mockito.Mockito;

public class NetworkControllerSignalTest extends NetworkControllerBaseTest {

    public void testNoIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm);
        setupNetworkController();

        verifyLastMobileDataIndicators(false, 0, 0);
    }

    public void testSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    DEFAULT_ICON);

            // Verify low inet number indexing.
            setConnectivity(10, ConnectivityManager.TYPE_MOBILE, true);
            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[0][testStrength], 0);
        }
    }

    public void testCdmaSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    TelephonyIcons.DATA_1X[1][0 /* No direction */]);
        }
    }

    public void testSignalRoaming() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setGsmRoaming(true);
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][testStrength],
                    TelephonyIcons.ROAMING_ICON);
        }
    }

    public void testCdmaSignalRoaming() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setCdmaRoaming(true);
            setLevel(testStrength);

            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH_ROAMING[1][testStrength],
                    TelephonyIcons.ROAMING_ICON);
        }
    }

    public void testQsSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setLevel(testStrength);

            verifyLastQsMobileDataIndicators(true,
                    TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    DEFAULT_QS_ICON, false, false, false);
        }
    }

    public void testCdmaQsSignalStrength() {
        for (int testStrength = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
                testStrength <= SignalStrength.SIGNAL_STRENGTH_GREAT; testStrength++) {
            setupDefaultSignal();
            setCdma();
            setLevel(testStrength);

            verifyLastQsMobileDataIndicators(true,
                    TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][testStrength],
                    TelephonyIcons.QS_ICON_1X, false, false, false);
        }
    }

    public void testNoRoamingWithoutSignal() {
        setupDefaultSignal();
        setCdma();
        setCdmaRoaming(true);
        setVoiceRegState(ServiceState.STATE_OUT_OF_SERVICE);
        setDataRegState(ServiceState.STATE_OUT_OF_SERVICE);

        // This exposes the bug in b/18034542, and should be switched to the commented out
        // verification below (and pass), once the bug is fixed.
        verifyLastMobileDataIndicators(true, R.drawable.stat_sys_signal_null,
                TelephonyIcons.ROAMING_ICON);
        //verifyLastMobileDataIndicators(true, R.drawable.stat_sys_signal_null, 0 /* No Icon */);
    }

    private void setCdma() {
        setIsGsm(false);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);
        setCdmaRoaming(false);
    }
}
