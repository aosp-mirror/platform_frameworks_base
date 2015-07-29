/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.policy;

import static org.mockito.Mockito.mock;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

@SmallTest
public class NetworkControllerSignalTest extends NetworkControllerBaseTest {

    public void testNoIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, Looper.getMainLooper(), mCallbackHandler,
                mock(AccessPointControllerImpl.class), mock(MobileDataControllerImpl.class),
                mMockSubDefaults);
        setupNetworkController();

        verifyLastMobileDataIndicators(false, 0, 0);
    }

    public void testNoSimsIconPresent() {
        // No Subscriptions.
        mNetworkController.mMobileSignalControllers.clear();
        mNetworkController.updateNoSims();

        verifyHasNoSims(true);
    }

    public void testEmergencyOnly() {
        setupDefaultSignal();
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(false);

        mMobileSignalController.getState().isEmergency = true;
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(true);
    }

    public void testEmergencyOnlyNoSubscriptions() {
        setupDefaultSignal();
        setSubscriptions();
        mNetworkController.mLastServiceState = new ServiceState();
        mNetworkController.mLastServiceState.setEmergencyOnly(true);
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(true);
    }

    public void testNoEmengencyNoSubscriptions() {
        setupDefaultSignal();
        setSubscriptions();
        mNetworkController.mLastServiceState = new ServiceState();
        mNetworkController.mLastServiceState.setEmergencyOnly(false);
        mNetworkController.recalculateEmergency();
        verifyEmergencyOnly(false);
    }

    public void testNoSimlessIconWithoutMobile() {
        // Turn off mobile network support.
        Mockito.when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
        // Create a new NetworkController as this is currently handled in constructor.
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, Looper.getMainLooper(), mCallbackHandler,
                mock(AccessPointControllerImpl.class), mock(MobileDataControllerImpl.class),
                mMockSubDefaults);
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
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][testStrength], DEFAULT_ICON);

            // Verify low inet number indexing.
            setConnectivity(NetworkCapabilities.TRANSPORT_CELLULAR, false, true);
            verifyLastMobileDataIndicators(true,
                    TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[0][testStrength], DEFAULT_ICON);
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
            setConnectivity(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);
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

    public void testNoBangWithWifi() {
        setupDefaultSignal();
        setConnectivity(mMobileSignalController.mTransportType, false, false);
        setConnectivity(NetworkCapabilities.TRANSPORT_WIFI, true, true);

        verifyLastMobileDataIndicators(true, TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][2], 0);
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

    public void testHistorySize() {
        // Verify valid history size, otherwise it gits printed out the wrong order and whatnot.
        assertEquals(0, SignalController.HISTORY_SIZE & (SignalController.HISTORY_SIZE - 1));
    }

    private void setCdma() {
        setIsGsm(false);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);
        setCdmaRoaming(false);
    }

    public void testOnReceive_stringsUpdatedAction_spn() {
        String expectedMNetworkName = "Test";
        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
                expectedMNetworkName /* spn */,
                false /* showPlmn */,
                "NotTest" /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(expectedMNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_plmn() {
        String expectedMNetworkName = "Test";

        Intent intent = createStringsUpdatedIntent(false /* showSpn */,
                "NotTest" /* spn */,
                true /* showPlmn */,
                expectedMNetworkName /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(expectedMNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_bothFalse() {
        Intent intent = createStringsUpdatedIntent(false /* showSpn */,
              "Irrelevant" /* spn */,
              false /* showPlmn */,
              "Irrelevant" /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        String defaultNetworkName = mMobileSignalController
            .getStringIfExists(
                com.android.internal.R.string.lockscreen_carrier_default);
        assertNetworkNameEquals(defaultNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_bothTrueAndNull() {
        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
            null /* spn */,
            true /* showPlmn */,
            null /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        String defaultNetworkName = mMobileSignalController.getStringIfExists(
                com.android.internal.R.string.lockscreen_carrier_default);
        assertNetworkNameEquals(defaultNetworkName);
    }

    public void testOnReceive_stringsUpdatedAction_bothTrueAndNonNull() {
        String spn = "Test1";
        String plmn = "Test2";

        Intent intent = createStringsUpdatedIntent(true /* showSpn */,
            spn /* spn */,
            true /* showPlmn */,
            plmn /* plmn */);

        mNetworkController.onReceive(mContext, intent);

        assertNetworkNameEquals(plmn
                + mMobileSignalController.getStringIfExists(
                        R.string.status_bar_network_name_separator)
                + spn);
    }

    private Intent createStringsUpdatedIntent(boolean showSpn, String spn,
            boolean showPlmn, String plmn) {

        Intent intent = new Intent();
        intent.setAction(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION);

        intent.putExtra(TelephonyIntents.EXTRA_SHOW_SPN, showSpn);
        intent.putExtra(TelephonyIntents.EXTRA_SPN, spn);

        intent.putExtra(TelephonyIntents.EXTRA_SHOW_PLMN, showPlmn);
        intent.putExtra(TelephonyIntents.EXTRA_PLMN, plmn);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mSubId);

        return intent;
    }

    public void testOnUpdateDataActivity_dataIn() {
        setupDefaultSignal();

        updateDataActivity(TelephonyManager.DATA_ACTIVITY_IN);

        verifyLastQsMobileDataIndicators(true /* visible */,
                TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
                DEFAULT_QS_ICON /* typeIcon */,
                true /* dataIn */,
                false /* dataOut */);

    }

    public void testOnUpdateDataActivity_dataOut() {
      setupDefaultSignal();

      updateDataActivity(TelephonyManager.DATA_ACTIVITY_OUT);

      verifyLastQsMobileDataIndicators(true /* visible */,
              TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
              DEFAULT_QS_ICON /* typeIcon */,
              false /* dataIn */,
              true /* dataOut */);

    }

    public void testOnUpdateDataActivity_dataInOut() {
      setupDefaultSignal();

      updateDataActivity(TelephonyManager.DATA_ACTIVITY_INOUT);

      verifyLastQsMobileDataIndicators(true /* visible */,
              TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
              DEFAULT_QS_ICON /* typeIcon */,
              true /* dataIn */,
              true /* dataOut */);

    }

    public void testOnUpdateDataActivity_dataActivityNone() {
      setupDefaultSignal();

      updateDataActivity(TelephonyManager.DATA_ACTIVITY_NONE);

      verifyLastQsMobileDataIndicators(true /* visible */,
              TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL] /* icon */,
              DEFAULT_QS_ICON /* typeIcon */,
              false /* dataIn */,
              false /* dataOut */);

    }

    public void testCarrierNetworkChange_carrierNetworkChange() {
      int strength = SignalStrength.SIGNAL_STRENGTH_GREAT;

      setupDefaultSignal();
      setLevel(strength);

      // Verify baseline
      verifyLastMobileDataIndicators(true /* visible */,
              TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][strength] /* strengthIcon */,
              DEFAULT_ICON /* typeIcon */);

      // API call is made
      setCarrierNetworkChange(true /* enabled */);

      // Carrier network change is true, show special indicator
      verifyLastMobileDataIndicators(true /* visible */,
              TelephonyIcons.TELEPHONY_CARRIER_NETWORK_CHANGE[0][0] /* strengthIcon */,
              0 /* typeIcon */);

      // Revert back
      setCarrierNetworkChange(false /* enabled */);

      // Verify back in previous state
      verifyLastMobileDataIndicators(true /* visible */,
              TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][strength] /* strengthIcon */,
              DEFAULT_ICON /* typeIcon */);
    }

    private void verifyEmergencyOnly(boolean isEmergencyOnly) {
        ArgumentCaptor<Boolean> emergencyOnly = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setEmergencyCallsOnly(
                emergencyOnly.capture());
        assertEquals(isEmergencyOnly, (boolean) emergencyOnly.getValue());
    }
}
