
package com.android.systemui.statusbar.policy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.internal.telephony.cdma.EriInfo;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.Config;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.SignalCluster;

public class NetworkControllerBaseTest extends AndroidTestCase {
    private static final String TAG = "NetworkControllerBaseTest";
    protected static final int DEFAULT_LEVEL = 2;
    protected static final int DEFAULT_SIGNAL_STRENGTH =
            TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL];
    protected static final int DEFAULT_QS_SIGNAL_STRENGTH =
            TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH[1][DEFAULT_LEVEL];
    protected static final int DEFAULT_ICON = TelephonyIcons.ICON_3G;
    protected static final int DEFAULT_QS_ICON = TelephonyIcons.QS_ICON_3G;

    protected NetworkControllerImpl mNetworkController;
    protected PhoneStateListener mPhoneStateListener;
    protected SignalCluster mSignalCluster;
    protected NetworkSignalChangedCallback mNetworkSignalChangedCallback;
    private SignalStrength mSignalStrength;
    private ServiceState mServiceState;
    protected ConnectivityManager mMockCm;
    protected WifiManager mMockWm;
    protected SubscriptionManager mMockSm;
    protected TelephonyManager mMockTm;
    protected Config mConfig;

    private NetworkCapabilities mNetCapabilities;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Mockito stuff.
        System.setProperty("dexmaker.dexcache", mContext.getCacheDir().getPath());
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        mMockWm = mock(WifiManager.class);
        mMockTm = mock(TelephonyManager.class);
        mMockSm = mock(SubscriptionManager.class);
        mMockCm = mock(ConnectivityManager.class);
        mNetCapabilities = new NetworkCapabilities();
        when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(true);
        when(mMockCm.getDefaultNetworkCapabilitiesForUser(0)).thenReturn(
                new NetworkCapabilities[] { mNetCapabilities });

        mSignalStrength = mock(SignalStrength.class);
        mServiceState = mock(ServiceState.class);

        mConfig = new Config();
        mConfig.hspaDataDistinguishable = true;
        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, mock(AccessPointControllerImpl.class),
                mock(MobileDataControllerImpl.class));
        setupNetworkController();
    }

    protected void setupNetworkController() {
        // For now just pretend to be the data sim, so we can test that too.
        final int subId = SubscriptionManager.getDefaultDataSubId();
        SubscriptionInfo subscription = mock(SubscriptionInfo.class);
        List<SubscriptionInfo> subs = new ArrayList<SubscriptionInfo>();
        when(subscription.getSubscriptionId()).thenReturn(subId);
        subs.add(subscription);
        mNetworkController.setCurrentSubscriptions(subs);
        mPhoneStateListener =
                mNetworkController.mMobileSignalControllers.get(subId).mPhoneStateListener;
        mSignalCluster = mock(SignalCluster.class);
        mNetworkSignalChangedCallback = mock(NetworkSignalChangedCallback.class);
        mNetworkController.addSignalCluster(mSignalCluster);
        mNetworkController.addNetworkSignalChangedCallback(mNetworkSignalChangedCallback);
    }

    @Override
    protected void tearDown() throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mNetworkController.dump(null, pw, null);
        pw.flush();
        Log.d(TAG, sw.toString());
        super.tearDown();
    }

    // 2 Bars 3G GSM.
    public void setupDefaultSignal() {
        setIsGsm(true);
        setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        setGsmRoaming(false);
        setLevel(DEFAULT_LEVEL);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_UMTS);
        setConnectivity(100, ConnectivityManager.TYPE_MOBILE, true);
    }

    public void setConnectivity(int inetCondition, int networkType, boolean isConnected) {
        Intent i = new Intent(ConnectivityManager.INET_CONDITION_ACTION);
        // TODO: Separate out into several NetworkCapabilities.
        if (isConnected) {
            mNetCapabilities.addTransportType(networkType);
        } else {
            mNetCapabilities.removeTransportType(networkType);
        }
        if (inetCondition != 0) {
            mNetCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            mNetCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }

        mNetworkController.onReceive(mContext, i);
    }

    public void setGsmRoaming(boolean isRoaming) {
        when(mServiceState.getRoaming()).thenReturn(isRoaming);
        updateServiceState();
    }

    public void setCdmaRoaming(boolean isRoaming) {
        when(mServiceState.getCdmaEriIconIndex()).thenReturn(isRoaming ?
                EriInfo.ROAMING_INDICATOR_ON : EriInfo.ROAMING_INDICATOR_OFF);
        when(mServiceState.getCdmaEriIconMode()).thenReturn(isRoaming ?
                EriInfo.ROAMING_ICON_MODE_NORMAL : -1);
        updateServiceState();
    }

    public void setVoiceRegState(int voiceRegState) {
        when(mServiceState.getVoiceRegState()).thenReturn(voiceRegState);
        updateServiceState();
    }

    public void setDataRegState(int dataRegState) {
        when(mServiceState.getDataRegState()).thenReturn(dataRegState);
        updateServiceState();
    }

    public void setIsEmergencyOnly(boolean isEmergency) {
        when(mServiceState.isEmergencyOnly()).thenReturn(isEmergency);
        updateServiceState();
    }

    public void setCdmaLevel(int level) {
        when(mSignalStrength.getCdmaLevel()).thenReturn(level);
        updateSignalStrength();
    }

    public void setLevel(int level) {
        when(mSignalStrength.getLevel()).thenReturn(level);
        updateSignalStrength();
    }

    public void setIsGsm(boolean gsm) {
        when(mSignalStrength.isGsm()).thenReturn(gsm);
        updateSignalStrength();
    }

    public void setCdmaEri(int index, int mode) {
        // TODO: Figure this out.
    }

    private void updateSignalStrength() {
        Log.d(TAG, "Sending Signal Strength: " + mSignalStrength);
        mPhoneStateListener.onSignalStrengthsChanged(mSignalStrength);
    }

    private void updateServiceState() {
        Log.d(TAG, "Sending Service State: " + mServiceState);
        mPhoneStateListener.onServiceStateChanged(mServiceState);
    }

    public void updateCallState(int state) {
        // Inputs not currently used in NetworkControllerImpl.
        mPhoneStateListener.onCallStateChanged(state, "0123456789");
    }

    public void updateDataConnectionState(int dataState, int dataNetType) {
        mPhoneStateListener.onDataConnectionStateChanged(dataState, dataNetType);
    }

    public void updateDataActivity(int dataActivity) {
        mPhoneStateListener.onDataActivity(dataActivity);
    }

    protected void verifyHasNoSims(boolean hasNoSimsVisible) {
        ArgumentCaptor<Boolean> hasNoSimsArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mSignalCluster, Mockito.atLeastOnce()).setNoSims(hasNoSimsArg.capture());
        assertEquals("No sims in status bar", hasNoSimsVisible, (boolean) hasNoSimsArg.getValue());

        Mockito.verify(mNetworkSignalChangedCallback, Mockito.atLeastOnce())
                .onNoSimVisibleChanged(hasNoSimsArg.capture());
        assertEquals("No sims in quick settings", hasNoSimsVisible,
                (boolean) hasNoSimsArg.getValue());
    }

    protected void verifyLastQsMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean dataIn, boolean dataOut) {
        ArgumentCaptor<Integer> iconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> visibleArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataInArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataOutArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mNetworkSignalChangedCallback, Mockito.atLeastOnce())
                .onMobileDataSignalChanged(visibleArg.capture(), iconArg.capture(),
                        ArgumentCaptor.forClass(String.class).capture(),
                        typeIconArg.capture(),
                        dataInArg.capture(),
                        dataOutArg.capture(),
                        ArgumentCaptor.forClass(String.class).capture(),
                        ArgumentCaptor.forClass(String.class).capture(),
                        ArgumentCaptor.forClass(Boolean.class).capture());
        assertEquals("Visibility in, quick settings", visible, (boolean) visibleArg.getValue());
        assertEquals("Signal icon in, quick settings", icon, (int) iconArg.getValue());
        assertEquals("Data icon in, quick settings", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Data direction in, in quick settings", dataIn,
                (boolean) dataInArg.getValue());
        assertEquals("Data direction out, in quick settings", dataOut,
                (boolean) dataOutArg.getValue());
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon) {
        ArgumentCaptor<Integer> iconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> visibleArg = ArgumentCaptor.forClass(Boolean.class);

        // TODO: Verify all fields.
        Mockito.verify(mSignalCluster, Mockito.atLeastOnce()).setMobileDataIndicators(
                visibleArg.capture(), iconArg.capture(), typeIconArg.capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture(),
                ArgumentCaptor.forClass(Integer.class).capture());

        assertEquals("Signal icon in status bar", icon, (int) iconArg.getValue());
        assertEquals("Data icon in status bar", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Visibility in status bar", visible, (boolean) visibleArg.getValue());
    }
}
