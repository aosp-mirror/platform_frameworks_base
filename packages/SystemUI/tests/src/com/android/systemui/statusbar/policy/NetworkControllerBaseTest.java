
package com.android.systemui.statusbar.policy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.systemui.statusbar.policy.NetworkControllerImpl.SignalCluster;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

public class NetworkControllerBaseTest extends AndroidTestCase {
    private static final String TAG = "NetworkControllerBaseTest";

    protected NetworkControllerImpl mNetworkController;
    protected PhoneStateListener mPhoneStateListener;
    protected SignalCluster mSignalCluster;
    private SignalStrength mSignalStrength;
    private ServiceState mServiceState;
    private ConnectivityManager mMockCM;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Mockito stuff.
        System.setProperty("dexmaker.dexcache", mContext.getCacheDir().getPath());
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        mMockCM = mock(ConnectivityManager.class);
        when(mMockCM.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(true);

        // TODO: Move away from fake, use spy if possible after MSIM refactor.
        mNetworkController = new FakeNetworkControllerImpl(mContext);

        mPhoneStateListener = mNetworkController.mPhoneStateListener;
        mSignalStrength = mock(SignalStrength.class);
        mServiceState = mock(ServiceState.class);
        mSignalCluster = mock(SignalCluster.class);
        mNetworkController.addSignalCluster(mSignalCluster);
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

    public void setConnectivity(int inetCondition, int networkType, boolean isConnected) {
        Intent i = new Intent(ConnectivityManager.INET_CONDITION_ACTION);
        NetworkInfo networkInfo = mock(NetworkInfo.class);
        when(networkInfo.isConnected()).thenReturn(isConnected);
        when(networkInfo.getType()).thenReturn(networkType);
        when(networkInfo.getTypeName()).thenReturn("");
        when(mMockCM.getActiveNetworkInfo()).thenReturn(networkInfo);

        i.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, inetCondition);
        mNetworkController.onReceive(mContext, i);
    }

    public void setGsmRoaming(boolean isRoaming) {
        when(mServiceState.getRoaming()).thenReturn(isRoaming);
        updateServiceState();
    }

    public void setVoiceRegState(int voiceRegState) {
        when(mServiceState.getVoiceRegState()).thenReturn(voiceRegState);
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

    protected void verifyLastMobileDataIndicators(boolean visible, int icon) {
        ArgumentCaptor<Integer> iconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> visibleArg = ArgumentCaptor.forClass(Boolean.class);

        // TODO: Verify all fields.
        Mockito.verify(mSignalCluster, Mockito.atLeastOnce()).setMobileDataIndicators(
                visibleArg.capture(), iconArg.capture(),
                ArgumentCaptor.forClass(Integer.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(String.class).capture(),
                ArgumentCaptor.forClass(Boolean.class).capture());

        assertEquals(icon, (int) iconArg.getValue());
        assertEquals(visible, (boolean) visibleArg.getValue());
    }

    private class FakeNetworkControllerImpl extends NetworkControllerImpl {
        public FakeNetworkControllerImpl(Context context) {
            super(context);
        }

        @Override
        public ConnectivityManager getCM() {
            return mMockCM;
        }

        public void registerListeners() {};
    }
}
