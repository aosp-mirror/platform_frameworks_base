package com.android.systemui.statusbar.policy;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.NetworkCapabilities;
import android.os.Looper;
import android.telephony.NetworkRegistrationState;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.settingslib.net.DataUsageController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NetworkControllerDataTest extends NetworkControllerBaseTest {

    @Test
    public void test3gDataIcon() {
        setupDefaultSignal();

        verifyDataIndicators(TelephonyIcons.ICON_3G);
    }

    @Test
    public void test2gDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_GSM);

        verifyDataIndicators(TelephonyIcons.ICON_G);
    }

    @Test
    public void testCdmaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);

        verifyDataIndicators(TelephonyIcons.ICON_1X);
    }

    @Test
    public void testEdgeDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_EDGE);

        verifyDataIndicators(TelephonyIcons.ICON_E);
    }

    @Test
    public void testLteDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_LTE);
    }

    @Test
    public void testHspaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPA);

        verifyDataIndicators(TelephonyIcons.ICON_H);
    }


    @Test
    public void testHspaPlusDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPAP);

        verifyDataIndicators(TelephonyIcons.ICON_H_PLUS);
    }


    @Test
    public void testWfcNoDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_IWLAN);

        verifyDataIndicators(0);
    }

    @Test
    public void test4gDataIcon() {
        // Switch to showing 4g icon and re-initialize the NetworkController.
        mConfig.show4gForLte = true;
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, Looper.getMainLooper(), mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(DataUsageController.class), mMockSubDefaults,
                mock(DeviceProvisionedController.class));
        setupNetworkController();

        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_4G);
    }

    @Test
    public void testNoInternetIcon_withDefaultSub() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED, 0);
        setConnectivityViaBroadcast(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, true, NO_DATA_STRING);
    }

    @Test
    public void testDataDisabledIcon_withDefaultSub() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivityViaBroadcast(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, true, NO_DATA_STRING);
    }

    @Test
    public void testNoInternetIcon_withoutDefaultSub() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        setDefaultSubId(mSubId + 1);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED, 0);
        setConnectivityViaBroadcast(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, true, NOT_DEFAULT_DATA_STRING);
    }

    @Test
    public void testDataDisabledIcon_withoutDefaultSub() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        setDefaultSubId(mSubId + 1);
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivityViaBroadcast(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);

        // Verify that a SignalDrawable with a cut out is used to display data disabled.
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, 0,
                true, DEFAULT_QS_SIGNAL_STRENGTH, 0, false,
                false, true, NOT_DEFAULT_DATA_STRING);
    }

    @Test
    public void testNr5GIcon_NrConnectedWithoutMMWave_show5GIcon() {
        setupDefaultNr5GIconConfiguration();
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);
        ServiceState ss = Mockito.mock(ServiceState.class);
        doReturn(NetworkRegistrationState.NR_STATUS_CONNECTED).when(ss).getNrStatus();
        doReturn(ServiceState.FREQUENCY_RANGE_HIGH).when(ss).getNrFrequencyRange();
        mPhoneStateListener.onServiceStateChanged(ss);

        verifyDataIndicators(TelephonyIcons.ICON_5G);
    }

    @Test
    public void testNr5GIcon_NrConnectedWithMMWave_show5GPlusIcon() {
        setupDefaultNr5GIconConfiguration();
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);
        ServiceState ss = Mockito.mock(ServiceState.class);
        doReturn(NetworkRegistrationState.NR_STATUS_CONNECTED).when(ss).getNrStatus();
        doReturn(ServiceState.FREQUENCY_RANGE_MMWAVE).when(ss).getNrFrequencyRange();
        mPhoneStateListener.onServiceStateChanged(ss);

        verifyDataIndicators(TelephonyIcons.ICON_5G_PLUS);
    }

    @Test
    public void testNr5GIcon_NrRestricted_showLteIcon() {
        setupDefaultNr5GIconConfiguration();
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);
        ServiceState ss = Mockito.mock(ServiceState.class);
        doReturn(NetworkRegistrationState.NR_STATUS_RESTRICTED).when(ss).getNrStatus();
        mPhoneStateListener.onServiceStateChanged(mServiceState);

        verifyDataIndicators(TelephonyIcons.ICON_LTE);
    }

    @Test
    public void testDataDisabledIcon_UserNotSetup() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivityViaBroadcast(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);
        when(mMockProvisionController.isUserSetup(anyInt())).thenReturn(false);
        mUserCallback.onUserSetupChanged();
        TestableLooper.get(this).processAllMessages();

        // Don't show the X until the device is setup.
        verifyDataIndicators(0);
    }

    @Test
    public void testAlwaysShowDataRatIcon() {
        setupDefaultSignal();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED,
                TelephonyManager.NETWORK_TYPE_GSM);

        // Switch to showing data RAT icon when data is disconnected
        // and re-initialize the NetworkController.
        mConfig.alwaysShowDataRatIcon = true;
        mNetworkController.handleConfigurationChanged();

        verifyDataIndicators(TelephonyIcons.ICON_G);
    }

    @Test
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

        verifyDataIndicators(TelephonyIcons.ICON_4G);
    }

    @Test
    public void testDataChangeWithoutConnectionState() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_LTE);

        when(mServiceState.getDataNetworkType())
                .thenReturn(TelephonyManager.NETWORK_TYPE_HSPA);
        updateServiceState();
        verifyDataIndicators(TelephonyIcons.ICON_H);
    }

    @Test
    public void testDataActivity() {
        setupDefaultSignal();

        testDataActivity(TelephonyManager.DATA_ACTIVITY_NONE, false, false);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_IN, true, false);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_OUT, false, true);
        testDataActivity(TelephonyManager.DATA_ACTIVITY_INOUT, true, true);
    }

    private void testDataActivity(int direction, boolean in, boolean out) {
        updateDataActivity(direction);

        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, DEFAULT_ICON, true,
                DEFAULT_QS_SIGNAL_STRENGTH, DEFAULT_QS_ICON, in, out);
    }

    private void verifyDataIndicators(int dataIcon) {
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, dataIcon,
                true, DEFAULT_QS_SIGNAL_STRENGTH, dataIcon, false,
                false);
    }
}
