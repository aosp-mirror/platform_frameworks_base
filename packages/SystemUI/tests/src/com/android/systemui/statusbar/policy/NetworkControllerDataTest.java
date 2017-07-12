package com.android.systemui.statusbar.policy;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.NetworkCapabilities;
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.settingslib.net.DataUsageController;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NetworkControllerDataTest extends NetworkControllerBaseTest {

    @Test
    @Ignore("Flaky")
    public void test3gDataIcon() {
        setupDefaultSignal();

        verifyDataIndicators(TelephonyIcons.ICON_3G,
                TelephonyIcons.QS_DATA_3G);
    }

    @Test
    @Ignore("Flaky")
    public void test2gDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_GSM);

        verifyDataIndicators(TelephonyIcons.ICON_G,
                TelephonyIcons.QS_DATA_G);
    }

    @Test
    @Ignore("Flaky")
    public void testCdmaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_CDMA);

        verifyDataIndicators(TelephonyIcons.ICON_1X,
                TelephonyIcons.QS_DATA_1X);
    }

    @Test
    @Ignore("Flaky")
    public void testEdgeDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_EDGE);

        verifyDataIndicators(TelephonyIcons.ICON_E,
                TelephonyIcons.QS_DATA_E);
    }

    @Test
    @Ignore("Flaky")
    public void testLteDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_LTE,
                TelephonyIcons.QS_DATA_LTE);
    }

    @Test
    @Ignore("Flaky")
    public void testHspaDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_HSPA);

        verifyDataIndicators(TelephonyIcons.ICON_H,
                TelephonyIcons.QS_DATA_H);
    }

    @Test
    @Ignore("Flaky")
    public void testWfcNoDataIcon() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_IWLAN);

        verifyDataIndicators(0, 0);
    }

    @Test
    @Ignore("Flaky")
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

        verifyDataIndicators(TelephonyIcons.ICON_4G,
                TelephonyIcons.QS_DATA_4G);
    }

    @Test
    @Ignore("Flaky")
    public void testDataDisabledIcon() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivity(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);

        verifyDataIndicators(TelephonyIcons.ICON_DATA_DISABLED,
                TelephonyIcons.QS_ICON_DATA_DISABLED);
    }

    @Test
    @Ignore("Flaky")
    public void testDataDisabledIcon_UserNotSetup() {
        setupNetworkController();
        when(mMockTm.getDataEnabled(mSubId)).thenReturn(false);
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_DISCONNECTED, 0);
        setConnectivity(NetworkCapabilities.TRANSPORT_CELLULAR, false, false);
        when(mMockProvisionController.isUserSetup(anyInt())).thenReturn(false);
        mUserCallback.onUserSetupChanged();

        // Don't show the X until the device is setup.
        verifyDataIndicators(0, 0);
    }

    @Test
    @Ignore("Flaky")
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

        verifyDataIndicators(TelephonyIcons.ICON_4G,
                TelephonyIcons.QS_DATA_4G);
    }

    @Test
    @Ignore("Flaky")
    public void testDataChangeWithoutConnectionState() {
        setupDefaultSignal();
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_LTE);

        verifyDataIndicators(TelephonyIcons.ICON_LTE,
                TelephonyIcons.QS_DATA_LTE);

        when(mServiceState.getDataNetworkType())
                .thenReturn(TelephonyManager.NETWORK_TYPE_HSPA);
        updateServiceState();
        verifyDataIndicators(TelephonyIcons.ICON_H,
                TelephonyIcons.QS_DATA_H);
    }

    @Test
    @Ignore("Flaky")
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

    private void verifyDataIndicators(int dataIcon, int qsDataIcon) {
        verifyLastMobileDataIndicators(true, DEFAULT_SIGNAL_STRENGTH, dataIcon,
                true, DEFAULT_QS_SIGNAL_STRENGTH, qsDataIcon, false,
                false);
    }

}
