package com.android.systemui.statusbar.policy;

import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.mockito.Mockito;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.MobileSignalController;

public class NetworkControllerSignalTest extends NetworkControllerBaseTest {

    public void testNoIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, mock(AccessPointControllerImpl.class),
                mock(MobileDataControllerImpl.class));
        setupNetworkController();

        verifyLastMobileDataIndicators(false, 0, 0);
    }

    public void testNoSimsIconPresent() {
        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(true);
    }

    public void testNoSimlessIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, mock(AccessPointControllerImpl.class),
                mock(MobileDataControllerImpl.class));
        setupNetworkController();

        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(false);
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
            setConnectivity(0, ConnectivityManager.TYPE_MOBILE, true);
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
                    DEFAULT_QS_ICON, false, false);
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
                    TelephonyIcons.QS_ICON_1X, false, false);
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

    // Some tests of actual NetworkController code, just internals not display stuff
    // TODO: Put this somewhere else, maybe in its own file.
    public void testHasCorrectMobileControllers() {
        int[] testSubscriptions = new int[] { 1, 5, 3 };
        int notTestSubscription = 0;
        MobileSignalController mobileSignalController = Mockito.mock(MobileSignalController.class);

        mNetworkController.mMobileSignalControllers.clear();
        List<SubscriptionInfo> subscriptions = new ArrayList<>();
        for (int i = 0; i < testSubscriptions.length; i++) {
            // Force the test controllers into NetworkController.
            mNetworkController.mMobileSignalControllers.put(testSubscriptions[i],
                    mobileSignalController);

            // Generate a list of subscriptions we will tell the NetworkController to use.
            SubscriptionInfo mockSubInfo = Mockito.mock(SubscriptionInfo.class);
            Mockito.when(mockSubInfo.getSubscriptionId()).thenReturn(testSubscriptions[i]);
            subscriptions.add(mockSubInfo);
        }
        assertTrue(mNetworkController.hasCorrectMobileControllers(subscriptions));

        // Add a subscription that the NetworkController doesn't know about.
        SubscriptionInfo mockSubInfo = Mockito.mock(SubscriptionInfo.class);
        Mockito.when(mockSubInfo.getSubscriptionId()).thenReturn(notTestSubscription);
        subscriptions.add(mockSubInfo);
        assertFalse(mNetworkController.hasCorrectMobileControllers(subscriptions));
    }

    public void testSetCurrentSubscriptions() {
        // We will not add one controller to make sure it gets created.
        int indexToSkipController = 0;
        // We will not add one subscription to make sure it's controller gets removed.
        int indexToSkipSubscription = 1;

        int[] testSubscriptions = new int[] { 1, 5, 3 };
        MobileSignalController[] mobileSignalControllers = new MobileSignalController[] {
                Mockito.mock(MobileSignalController.class),
                Mockito.mock(MobileSignalController.class),
                Mockito.mock(MobileSignalController.class),
        };
        mNetworkController.mMobileSignalControllers.clear();
        List<SubscriptionInfo> subscriptions = new ArrayList<>();
        for (int i = 0; i < testSubscriptions.length; i++) {
            if (i != indexToSkipController) {
                // Force the test controllers into NetworkController.
                mNetworkController.mMobileSignalControllers.put(testSubscriptions[i],
                        mobileSignalControllers[i]);
            }

            if (i != indexToSkipSubscription) {
                // Generate a list of subscriptions we will tell the NetworkController to use.
                SubscriptionInfo mockSubInfo = Mockito.mock(SubscriptionInfo.class);
                Mockito.when(mockSubInfo.getSubscriptionId()).thenReturn(testSubscriptions[i]);
                Mockito.when(mockSubInfo.getSimSlotIndex()).thenReturn(testSubscriptions[i]);
                subscriptions.add(mockSubInfo);
            }
        }

        // We can only test whether unregister gets called if it thinks its in a listening
        // state.
        mNetworkController.mListening = true;
        mNetworkController.setCurrentSubscriptions(subscriptions);

        for (int i = 0; i < testSubscriptions.length; i++) {
            if (i == indexToSkipController) {
                // Make sure a controller was created despite us not adding one.
                assertTrue(mNetworkController.mMobileSignalControllers.containsKey(
                        testSubscriptions[i]));
            } else if (i == indexToSkipSubscription) {
                // Make sure the controller that did exist was removed
                assertFalse(mNetworkController.mMobileSignalControllers.containsKey(
                        testSubscriptions[i]));
            } else {
                // If a MobileSignalController is around it needs to not be unregistered.
                Mockito.verify(mobileSignalControllers[i], Mockito.never())
                        .unregisterListener();
            }
        }
    }

    private void setCdma() {
        setIsGsm(false);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);
        setCdmaRoaming(false);
    }
}
