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

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.internal.telephony.cdma.EriInfo;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.Config;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.SubscriptionDefaults;

import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public class NetworkControllerBaseTest extends SysuiTestCase {
    private static final String TAG = "NetworkControllerBaseTest";
    protected static final int DEFAULT_LEVEL = 2;
    protected static final int DEFAULT_SIGNAL_STRENGTH = DEFAULT_LEVEL;
    protected static final int DEFAULT_QS_SIGNAL_STRENGTH = DEFAULT_LEVEL;
    protected static final int DEFAULT_ICON = TelephonyIcons.ICON_3G;
    protected static final int DEFAULT_QS_ICON = TelephonyIcons.ICON_3G;
    protected static final String NO_DATA_STRING = "Data disabled";
    protected static final String NOT_DEFAULT_DATA_STRING = "Not default data";

    protected NetworkControllerImpl mNetworkController;
    protected MobileSignalController mMobileSignalController;
    protected PhoneStateListener mPhoneStateListener;
    protected SignalStrength mSignalStrength;
    protected ServiceState mServiceState;
    protected TelephonyDisplayInfo mTelephonyDisplayInfo;
    protected ConnectivityManager mMockCm;
    protected WifiManager mMockWm;
    protected SubscriptionManager mMockSm;
    protected TelephonyManager mMockTm;
    protected Config mConfig;
    protected CallbackHandler mCallbackHandler;
    protected SubscriptionDefaults mMockSubDefaults;
    protected DeviceProvisionedController mMockProvisionController;
    protected DeviceProvisionedListener mUserCallback;
    protected Instrumentation mInstrumentation;

    protected int mSubId;

    private NetworkCapabilities mNetCapabilities;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    @Rule
    public TestWatcher failWatcher = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            // Print out mNetworkController state if the test fails.
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            mNetworkController.dump(null, pw, null);
            pw.flush();
            Log.d(TAG, sw.toString());
        }
    };

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        Settings.Global.putInt(mContext.getContentResolver(), Global.AIRPLANE_MODE_ON, 0);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(R.string.cell_data_off_content_description, NO_DATA_STRING);
        res.addOverride(R.string.not_default_data_content_description, NOT_DEFAULT_DATA_STRING);

        mMockWm = mock(WifiManager.class);
        mMockTm = mock(TelephonyManager.class);
        mMockSm = mock(SubscriptionManager.class);
        mMockCm = mock(ConnectivityManager.class);
        mMockSubDefaults = mock(SubscriptionDefaults.class);
        mNetCapabilities = new NetworkCapabilities();
        when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(true);
        when(mMockCm.getDefaultNetworkCapabilitiesForUser(0)).thenReturn(
                new NetworkCapabilities[] { mNetCapabilities });
        when(mMockTm.createForSubscriptionId(anyInt())).thenReturn(mMockTm);

        mSignalStrength = mock(SignalStrength.class);
        mServiceState = mock(ServiceState.class);
        mTelephonyDisplayInfo = mock(TelephonyDisplayInfo.class);

        mConfig = new Config();
        mConfig.hspaDataDistinguishable = true;
        mCallbackHandler = mock(CallbackHandler.class);

        mMockProvisionController = mock(DeviceProvisionedController.class);
        when(mMockProvisionController.isUserSetup(anyInt())).thenReturn(true);
        doAnswer(invocation -> {
            mUserCallback = (DeviceProvisionedListener) invocation.getArguments()[0];
            mUserCallback.onUserSetupChanged();
            mUserCallback.onDeviceProvisionedChanged();
            TestableLooper.get(this).processAllMessages();
            return null;
        }).when(mMockProvisionController).addCallback(any());

        mNetworkController = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                mConfig, TestableLooper.get(this).getLooper(), mCallbackHandler,
                mock(AccessPointControllerImpl.class), mock(DataUsageController.class),
                mMockSubDefaults, mMockProvisionController);
        setupNetworkController();

        // Trigger blank callbacks to always get the current state (some tests don't trigger
        // changes from default state).
        mNetworkController.addCallback(mock(SignalCallback.class));
        mNetworkController.addEmergencyListener(null);
    }

    protected void setupNetworkController() {
        // For now just pretend to be the data sim, so we can test that too.
        mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
        when(mMockTm.isDataConnectionAllowed()).thenReturn(true);
        setDefaultSubId(mSubId);
        setSubscriptions(mSubId);
        mMobileSignalController = mNetworkController.mMobileSignalControllers.get(mSubId);
        mPhoneStateListener = mMobileSignalController.mPhoneStateListener;

        ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackArg =
            ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        Mockito.verify(mMockCm, atLeastOnce())
            .registerDefaultNetworkCallback(callbackArg.capture(), isA(Handler.class));
        mNetworkCallback = callbackArg.getValue();
        assertNotNull(mNetworkCallback);
    }

    protected void setDefaultSubId(int subId) {
        when(mMockSubDefaults.getDefaultDataSubId()).thenReturn(subId);
        when(mMockSubDefaults.getDefaultVoiceSubId()).thenReturn(subId);
        when(mMockSubDefaults.getActiveDataSubId()).thenReturn(subId);
    }

    protected void setSubscriptions(int... subIds) {
        List<SubscriptionInfo> subs = new ArrayList<SubscriptionInfo>();
        for (int subId : subIds) {
            SubscriptionInfo subscription = mock(SubscriptionInfo.class);
            when(subscription.getSubscriptionId()).thenReturn(subId);
            subs.add(subscription);
        }
        when(mMockSm.getActiveSubscriptionInfoList()).thenReturn(subs);
        when(mMockSm.getActiveSubscriptionInfoList(anyBoolean())).thenReturn(subs);
        mNetworkController.doUpdateMobileControllers();
    }

    protected NetworkControllerImpl setUpNoMobileData() {
      when(mMockCm.isNetworkSupported(ConnectivityManager.TYPE_MOBILE)).thenReturn(false);
      NetworkControllerImpl networkControllerNoMobile
              = new NetworkControllerImpl(mContext, mMockCm, mMockTm, mMockWm, mMockSm,
                        mConfig, TestableLooper.get(this).getLooper(), mCallbackHandler,
                        mock(AccessPointControllerImpl.class),
                        mock(DataUsageController.class), mMockSubDefaults,
                        mock(DeviceProvisionedController.class));

      setupNetworkController();

      return networkControllerNoMobile;

    }

    // 2 Bars 3G GSM.
    public void setupDefaultSignal() {
        setIsGsm(true);
        setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        setGsmRoaming(false);
        setLevel(DEFAULT_LEVEL);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_UMTS);
        setConnectivityViaBroadcast(
            NetworkCapabilities.TRANSPORT_CELLULAR, true, true);
    }

    public void setConnectivityViaBroadcast(
        int networkType, boolean validated, boolean isConnected) {
        setConnectivityCommon(networkType, validated, isConnected);
        Intent i = new Intent(ConnectivityManager.INET_CONDITION_ACTION);
        mNetworkController.onReceive(mContext, i);
    }

    public void setConnectivityViaCallback(
        int networkType, boolean validated, boolean isConnected){
        setConnectivityCommon(networkType, validated, isConnected);
        mNetworkCallback.onCapabilitiesChanged(
            mock(Network.class), new NetworkCapabilities(mNetCapabilities));
    }

    private void setConnectivityCommon(
        int networkType, boolean validated, boolean isConnected){
        // TODO: Separate out into several NetworkCapabilities.
        if (isConnected) {
            mNetCapabilities.addTransportType(networkType);
        } else {
            mNetCapabilities.removeTransportType(networkType);
        }
        if (validated) {
            mNetCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            mNetCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
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
        when(mServiceState.getState()).thenReturn(voiceRegState);
        updateServiceState();
    }

    public void setDataRegState(int dataRegState) {
        when(mServiceState.getDataRegistrationState()).thenReturn(dataRegState);
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

    protected void updateServiceState() {
        Log.d(TAG, "Sending Service State: " + mServiceState);
        mPhoneStateListener.onServiceStateChanged(mServiceState);
        mPhoneStateListener.onDisplayInfoChanged(mTelephonyDisplayInfo);
    }

    public void updateCallState(int state) {
        // Inputs not currently used in NetworkControllerImpl.
        mPhoneStateListener.onCallStateChanged(state, "0123456789");
    }

    public void updateDataConnectionState(int dataState, int dataNetType) {
        NetworkRegistrationInfo fakeRegInfo = new NetworkRegistrationInfo.Builder()
                .setTransportType(TRANSPORT_TYPE_WWAN)
                .setDomain(DOMAIN_PS)
                .setAccessNetworkTechnology(dataNetType)
                .build();
        when(mServiceState.getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN))
                .thenReturn(fakeRegInfo);
        when(mTelephonyDisplayInfo.getNetworkType()).thenReturn(dataNetType);
        mPhoneStateListener.onDataConnectionStateChanged(dataState, dataNetType);
    }

    public void updateDataActivity(int dataActivity) {
        mPhoneStateListener.onDataActivity(dataActivity);
    }

    public void setCarrierNetworkChange(boolean enable) {
        Log.d(TAG, "setCarrierNetworkChange(" + enable + ")");
        mPhoneStateListener.onCarrierNetworkChange(enable);
    }

    protected void verifyHasNoSims(boolean hasNoSimsVisible) {
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setNoSims(
                eq(hasNoSimsVisible), eq(false));
    }

    protected void verifyLastQsMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean dataIn, boolean dataOut) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> dataInArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataOutArg = ArgumentCaptor.forClass(Boolean.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                    any(),
                    iconArg.capture(),
                    anyInt(),
                    typeIconArg.capture(), dataInArg.capture(), dataOutArg.capture(),
                    anyString(), anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean());
        IconState iconState = iconArg.getValue();
        int state = SignalDrawable.getState(icon, SignalStrength.NUM_SIGNAL_STRENGTH_BINS,
                false);
        assertEquals("Visibility in, quick settings", visible, iconState.visible);
        assertEquals("Signal icon in, quick settings", state, iconState.icon);
        assertEquals("Data icon in, quick settings", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Data direction in, in quick settings", dataIn,
                (boolean) dataInArg.getValue());
        assertEquals("Data direction out, in quick settings", dataOut,
                (boolean) dataOutArg.getValue());
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon) {
        verifyLastMobileDataIndicators(visible, icon, typeIcon, false);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean roaming) {
        verifyLastMobileDataIndicators(visible, icon, typeIcon, roaming, true);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
        boolean roaming, boolean inet) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);

        // TODO: Verify all fields.
        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                iconArg.capture(),
                any(),
                typeIconArg.capture(),
                anyInt(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyString(),
                anyBoolean(), anyInt(), eq(roaming));
        IconState iconState = iconArg.getValue();

        int state = icon == -1 ? 0
                : SignalDrawable.getState(icon, SignalStrength.NUM_SIGNAL_STRENGTH_BINS, !inet);
        assertEquals("Signal icon in status bar", state, iconState.icon);
        assertEquals("Data icon in status bar", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Visibility in status bar", visible, iconState.visible);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean qsVisible, int qsIcon, int qsTypeIcon, boolean dataIn, boolean dataOut) {
        verifyLastMobileDataIndicators(
                visible, icon, typeIcon, qsVisible, qsIcon, qsTypeIcon, dataIn, dataOut, false);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean qsVisible, int qsIcon, int qsTypeIcon, boolean dataIn, boolean dataOut,
            boolean cutOut) {
        verifyLastMobileDataIndicators(
                visible, icon, typeIcon, qsVisible, qsIcon, qsTypeIcon, dataIn, dataOut, cutOut,
                null);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean qsVisible, int qsIcon, int qsTypeIcon, boolean dataIn, boolean dataOut,
            boolean cutOut, String typeContentDescription) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<IconState> qsIconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> qsTypeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> dataInArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataOutArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> typeContentDescriptionArg = ArgumentCaptor.forClass(String.class);

        Mockito.verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                iconArg.capture(),
                qsIconArg.capture(),
                typeIconArg.capture(),
                qsTypeIconArg.capture(),
                dataInArg.capture(),
                dataOutArg.capture(),
                typeContentDescriptionArg.capture(),
                typeContentDescriptionArg.capture(),
                anyString(), anyBoolean(), anyInt(), anyBoolean());

        IconState iconState = iconArg.getValue();

        int numSignalStrengthBins = SignalStrength.NUM_SIGNAL_STRENGTH_BINS;
        if (mMobileSignalController.mInflateSignalStrengths) {
            numSignalStrengthBins++;
            icon++;
        }
        int state = SignalDrawable.getState(icon, numSignalStrengthBins, cutOut);
        assertEquals("Data icon in status bar", typeIcon, (int) typeIconArg.getValue());
        assertEquals("Signal icon in status bar", state, iconState.icon);
        assertEquals("Visibility in status bar", visible, iconState.visible);

        iconState = qsIconArg.getValue();
        assertEquals("Visibility in quick settings", qsVisible, iconState.visible);
        assertEquals("Signal icon in quick settings", state, iconState.icon);
        assertEquals("Data icon in quick settings", qsTypeIcon, (int) qsTypeIconArg.getValue());
        assertEquals("Data direction in in quick settings", dataIn,
                (boolean) dataInArg.getValue());
        assertEquals("Data direction out in quick settings", dataOut,
                (boolean) dataOutArg.getValue());
        if (typeContentDescription != null) { // Only check if it was provided
            assertEquals("Type content description", typeContentDescription,
                    typeContentDescriptionArg.getValue());
        }
    }

    protected void assertNetworkNameEquals(String expected) {
       assertEquals("Network name", expected, mMobileSignalController.getState().networkName);
    }

    protected void assertDataNetworkNameEquals(String expected) {
        assertEquals("Data network name", expected, mNetworkController.getMobileDataNetworkName());
    }

}
