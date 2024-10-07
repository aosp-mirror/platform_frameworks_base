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

package com.android.systemui.statusbar.connectivity;

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.NetworkRegistrationInfo.DOMAIN_PS;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_DENIED;
import static android.telephony.NetworkRegistrationInfo.REGISTRATION_STATE_HOME;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkScoreManager;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.CellSignalStrength;
import android.telephony.NetworkRegistrationInfo;
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

import com.android.settingslib.R;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.MobileMappings.Config;
import com.android.settingslib.mobile.MobileStatusTracker.SubscriptionDefaults;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.DataUsageController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags;
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
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
    protected SignalStrength mSignalStrength;
    protected ServiceState mServiceState;
    protected TelephonyDisplayInfo mTelephonyDisplayInfo;
    protected NetworkRegistrationInfo mFakeRegInfo;
    protected ConnectivityManager mMockCm;
    protected WifiManager mMockWm;
    protected NetworkScoreManager mMockNsm;
    protected SubscriptionManager mMockSm;
    protected TelephonyManager mMockTm;
    protected TelephonyListenerManager mTelephonyListenerManager;
    protected BroadcastDispatcher mMockBd;
    protected UserTracker mUserTracker;
    protected Config mConfig;
    protected CallbackHandler mCallbackHandler;
    protected SubscriptionDefaults mMockSubDefaults;
    protected DeviceProvisionedController mMockProvisionController;
    protected DeviceProvisionedListener mUserCallback;
    protected Instrumentation mInstrumentation;
    protected DemoModeController mDemoModeController;
    protected CarrierConfigTracker mCarrierConfigTracker;
    protected FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    protected Handler mMainHandler;
    // Use a real mobile mappings object since lots of tests rely on it
    protected FakeMobileMappingsProxy mMobileMappingsProxy = new FakeMobileMappingsProxy();
    protected WifiStatusTrackerFactory mWifiStatusTrackerFactory;
    protected MobileSignalControllerFactory mMobileFactory;

    protected int mSubId;

    private NetworkCapabilities mNetCapabilities;
    private ConnectivityManager.NetworkCallback mDefaultCallbackInWifiTracker;
    private ConnectivityManager.NetworkCallback mDefaultCallbackInNetworkController;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    MockitoSession mMockingSession = null;

    @Rule
    public TestWatcher failWatcher = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            if (mNetworkController == null) {
                Log.d(TAG, "mNetworkController = null!");
                return;
            }
            // Print out mNetworkController state if the test fails.
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            mNetworkController.dump(pw, null);
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

        mDemoModeController = mock(DemoModeController.class);
        mMockWm = mock(WifiManager.class);
        mMockTm = mock(TelephonyManager.class);
        mTelephonyListenerManager = mock(TelephonyListenerManager.class);
        mMockSm = mock(SubscriptionManager.class);
        mMockCm = mock(ConnectivityManager.class);
        mMockBd = mock(BroadcastDispatcher.class);
        mUserTracker = mock(UserTracker.class);
        mMockNsm = mock(NetworkScoreManager.class);
        mMockSubDefaults = mock(SubscriptionDefaults.class);
        mCarrierConfigTracker = mock(CarrierConfigTracker.class);
        mMainHandler = mock(Handler.class);
        mNetCapabilities = new NetworkCapabilities();
        when(mMockTm.isDataCapable()).thenReturn(true);
        when(mMockTm.createForSubscriptionId(anyInt())).thenReturn(mMockTm);

        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mMainHandler).post(any());
        doAnswer(invocation -> {
            int rssi = invocation.getArgument(0);
            if (rssi < -88) return 0;
            if (rssi < -77) return 1;
            if (rssi < -66) return 2;
            if (rssi < -55) return 3;
            return 4;
        }).when(mMockWm).calculateSignalLevel(anyInt());
        when(mMockWm.getMaxSignalLevel()).thenReturn(4);

        mSignalStrength = mock(SignalStrength.class);
        mServiceState = mock(ServiceState.class);
        mTelephonyDisplayInfo = mock(TelephonyDisplayInfo.class);

        mFakeRegInfo = new NetworkRegistrationInfo.Builder()
                .setTransportType(TRANSPORT_TYPE_WWAN)
                .setDomain(DOMAIN_PS)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_LTE)
                .build();
        doReturn(mFakeRegInfo).when(mServiceState)
                .getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN);
        doReturn(TelephonyManager.NETWORK_TYPE_LTE).when(mTelephonyDisplayInfo).getNetworkType();
        doReturn(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE).when(mTelephonyDisplayInfo)
                .getOverrideNetworkType();
        mConfig = new Config();
        mConfig.hspaDataDistinguishable = true;
        mCallbackHandler = mock(CallbackHandler.class);

        mMockProvisionController = mock(DeviceProvisionedController.class);
        when(mMockProvisionController.isCurrentUserSetup()).thenReturn(true);
        doAnswer(invocation -> {
            mUserCallback = (DeviceProvisionedListener) invocation.getArguments()[0];
            TestableLooper.get(this).processAllMessages();
            return null;
        }).when(mMockProvisionController).addCallback(any());

        mWifiStatusTrackerFactory = new WifiStatusTrackerFactory(
                mContext, mMockWm, mMockNsm, mMockCm, mMainHandler);
        // Most of these tests rely on the actual MobileMappings behavior
        mMobileMappingsProxy.setUseRealImpl(true);
        mMobileFactory = new MobileSignalControllerFactory(
                mContext,
                mCallbackHandler,
                mCarrierConfigTracker,
                mMobileMappingsProxy
        );

        mNetworkController = new NetworkControllerImpl(mContext,
                mMockCm,
                mMockTm,
                mTelephonyListenerManager,
                mMockWm,
                mMockSm,
                mConfig,
                TestableLooper.get(this).getLooper(),
                mFakeExecutor,
                mCallbackHandler,
                mock(AccessPointControllerImpl.class),
                mock(StatusBarPipelineFlags.class),
                mock(DataUsageController.class),
                mMockSubDefaults,
                mMockProvisionController,
                mMockBd,
                mUserTracker,
                mDemoModeController,
                mCarrierConfigTracker,
                mWifiStatusTrackerFactory,
                mMobileFactory,
                mMainHandler,
                mock(DumpManager.class),
                mock(LogBuffer.class)
        );
        setupNetworkController();

        // Trigger blank callbacks to always get the current state (some tests don't trigger
        // changes from default state).
        mNetworkController.addCallback(mock(SignalCallback.class));
        mNetworkController.addEmergencyListener(null);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    protected void setupNetworkController() {
        // For now just pretend to be the data sim, so we can test that too.
        mSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
        when(mMockTm.isDataConnectionAllowed()).thenReturn(true);
        setDefaultSubId(mSubId);
        setSubscriptions(mSubId);
        mMobileSignalController = mNetworkController.mMobileSignalControllers.get(mSubId);
        ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackArg =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mMockCm, atLeastOnce())
            .registerDefaultNetworkCallback(callbackArg.capture(), isA(Handler.class));
        int captureSize = callbackArg.getAllValues().size();
        assertTrue(captureSize > 1);
        assertEquals(captureSize % 2, 0);
        mDefaultCallbackInWifiTracker = callbackArg.getAllValues().get(captureSize - 2);
        mDefaultCallbackInNetworkController = callbackArg.getAllValues().get(captureSize - 1);
        assertNotNull(mDefaultCallbackInWifiTracker);
        assertNotNull(mDefaultCallbackInNetworkController);
        verify(mMockCm, atLeastOnce()).registerNetworkCallback(
                isA(NetworkRequest.class), callbackArg.capture(), isA(Handler.class));
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
        when(mMockSm.getCompleteActiveSubscriptionInfoList()).thenReturn(subs);
        mNetworkController.doUpdateMobileControllers();
    }

    // 2 Bars 3G GSM.
    public void setupDefaultSignal() {
        setIsGsm(true);
        setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        setGsmRoaming(false);
        setLevel(DEFAULT_LEVEL);
        updateDataConnectionState(TelephonyManager.DATA_CONNECTED,
                TelephonyManager.NETWORK_TYPE_UMTS);
        setConnectivityViaCallbackInNetworkController(
                NetworkCapabilities.TRANSPORT_CELLULAR, true, true, null);
    }

    public void setConnectivityViaCallbackInNetworkControllerForVcn(
            int networkType,
            boolean validated,
            boolean isConnected,
            VcnTransportInfo info,
            Network underlyingNetwork) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(mNetCapabilities);
        builder.setTransportInfo(info)
                .setUnderlyingNetworks(Collections.singletonList(underlyingNetwork));
        setConnectivityCommon(builder, networkType, validated, isConnected);
        mDefaultCallbackInNetworkController.onCapabilitiesChanged(
                mock(Network.class), builder.build());
    }

    public void setConnectivityViaCallbackInNetworkController(
            Network network, NetworkCapabilities networkCapabilities) {
        mDefaultCallbackInNetworkController.onCapabilitiesChanged(network, networkCapabilities);
    }

    public void setConnectivityViaCallbackInNetworkController(
            int networkType, boolean validated, boolean isConnected, WifiInfo wifiInfo) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(mNetCapabilities);
        if (networkType == NetworkCapabilities.TRANSPORT_WIFI) {
            builder.setTransportInfo(wifiInfo);
        }
        setConnectivityCommon(builder, networkType, validated, isConnected);
        mDefaultCallbackInNetworkController.onCapabilitiesChanged(
                mock(Network.class), builder.build());
    }

    public void setConnectivityViaDefaultAndNormalCallbackInWifiTracker(
            Network network, NetworkCapabilities networkCapabilities) {
        mNetworkCallback.onAvailable(network);
        mNetworkCallback.onCapabilitiesChanged(network, networkCapabilities);
        mDefaultCallbackInWifiTracker.onCapabilitiesChanged(network, networkCapabilities);
    }

    public void setConnectivityViaCallbackInWifiTracker(
            int networkType, boolean validated, boolean isConnected, WifiInfo wifiInfo) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(mNetCapabilities);
        if (networkType == NetworkCapabilities.TRANSPORT_WIFI) {
            builder.setTransportInfo(wifiInfo);
        }
        setConnectivityCommon(builder, networkType, validated, isConnected);
        if (networkType == NetworkCapabilities.TRANSPORT_WIFI) {
            if (isConnected) {
                final NetworkCapabilities newCap = builder.build();
                mNetworkCallback.onAvailable(mock(Network.class));
                mNetworkCallback.onCapabilitiesChanged(mock(Network.class), newCap);
            } else {
                mNetworkCallback.onLost(mock(Network.class));
            }
        }
    }

    public void setConnectivityViaCallbackInWifiTrackerForVcn(
            int networkType,
            boolean validated,
            boolean isConnected,
            VcnTransportInfo info,
            Network underlyingNetwork) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(mNetCapabilities);
        builder.setTransportInfo(info)
                .setUnderlyingNetworks(Collections.singletonList(underlyingNetwork));
        setConnectivityCommon(builder, networkType, validated, isConnected);
        if (networkType == NetworkCapabilities.TRANSPORT_CELLULAR) {
            if (isConnected) {
                final NetworkCapabilities newCap = builder.build();
                mNetworkCallback.onAvailable(mock(Network.class));
                mNetworkCallback.onCapabilitiesChanged(mock(Network.class), newCap);
                mDefaultCallbackInWifiTracker.onCapabilitiesChanged(mock(Network.class), newCap);
            } else {
                mNetworkCallback.onLost(mock(Network.class));
            }
        }
    }

    public void setConnectivityViaDefaultCallbackInWifiTracker(
            int networkType, boolean validated, boolean isConnected, WifiInfo wifiInfo) {
        final NetworkCapabilities.Builder builder =
                new NetworkCapabilities.Builder(mNetCapabilities);
        if (networkType == NetworkCapabilities.TRANSPORT_WIFI) {
            builder.setTransportInfo(wifiInfo);
        }
        setConnectivityCommon(builder, networkType, validated, isConnected);
        mDefaultCallbackInWifiTracker.onCapabilitiesChanged(
                mock(Network.class), builder.build());
    }

    private static void setConnectivityCommon(NetworkCapabilities.Builder builder,
            int networkType, boolean validated, boolean isConnected) {
        // TODO: Separate out into several NetworkCapabilities.
        if (isConnected) {
            builder.addTransportType(networkType);
        } else {
            builder.removeTransportType(networkType);
        }
        if (validated) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
    }

    public void setGsmRoaming(boolean isRoaming) {
        when(mServiceState.getRoaming()).thenReturn(isRoaming);
        updateServiceState();
    }

    public void setCdmaRoaming(boolean isRoaming) {
        when(mMockTm.getCdmaEnhancedRoamingIndicatorDisplayNumber()).thenReturn(
                isRoaming ? TelephonyManager.ERI_ON : TelephonyManager.ERI_OFF);
    }

    public void setVoiceRegState(int voiceRegState) {
        when(mServiceState.getState()).thenReturn(voiceRegState);
        when(mServiceState.getVoiceRegState()).thenReturn(voiceRegState);
        updateServiceState();
    }

    public void setDataRegInService(boolean inService) {
        // mFakeRegInfo#isInService()
        // Utils#isInService uses NetworkRegistrationInfo#isInService(). Since we can't
        // mock the answer here, just set the bit based on what the caller wants
        NetworkRegistrationInfo.Builder builder = new NetworkRegistrationInfo.Builder()
                .setTransportType(TRANSPORT_TYPE_WWAN)
                .setDomain(DOMAIN_PS)
                .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_LTE);

        if (inService) {
            builder.setRegistrationState(REGISTRATION_STATE_HOME);
        } else {
            builder.setRegistrationState(REGISTRATION_STATE_DENIED);
        }

        NetworkRegistrationInfo fakeRegInfo = builder.build();
        when(mServiceState.getNetworkRegistrationInfo(DOMAIN_PS, TRANSPORT_TYPE_WWAN))
                .thenReturn(fakeRegInfo);

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
        mMobileSignalController.mMobileStatusTracker.getTelephonyCallback()
                .onSignalStrengthsChanged(mSignalStrength);
    }

    protected void updateServiceState() {
        Log.d(TAG, "Sending Service State: " + mServiceState);
        mMobileSignalController.mMobileStatusTracker.getTelephonyCallback()
                .onServiceStateChanged(mServiceState);
        mMobileSignalController.mMobileStatusTracker.getTelephonyCallback()
                .onDisplayInfoChanged(mTelephonyDisplayInfo);
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
        mMobileSignalController.mMobileStatusTracker.getTelephonyCallback()
                .onDataConnectionStateChanged(dataState, dataNetType);
    }

    public void updateDataActivity(int dataActivity) {
        mMobileSignalController.mMobileStatusTracker.getTelephonyCallback()
                .onDataActivity(dataActivity);
    }

    public void setCarrierNetworkChange(boolean enable) {
        Log.d(TAG, "setCarrierNetworkChange(" + enable + ")");
        mMobileSignalController.mMobileStatusTracker.getTelephonyCallback()
                .onCarrierNetworkChange(enable);
    }

    protected void verifyHasNoSims(boolean hasNoSimsVisible) {
        verify(mCallbackHandler, Mockito.atLeastOnce()).setNoSims(
                eq(hasNoSimsVisible), eq(false));
    }

    protected void verifyLastQsMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean dataIn, boolean dataOut) {
        ArgumentCaptor<MobileDataIndicators> indicatorsArg =
                ArgumentCaptor.forClass(MobileDataIndicators.class);

        verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                    indicatorsArg.capture());
        MobileDataIndicators expected = indicatorsArg.getValue();
        int state = SignalDrawable.getState(icon, CellSignalStrength.getNumSignalStrengthLevels(),
                false);
        assertEquals("Visibility in, quick settings", visible, expected.qsIcon.visible);
        assertEquals("Signal icon in, quick settings", state, expected.qsIcon.icon);
        assertEquals("Data icon in, quick settings", typeIcon, expected.qsType);
        assertEquals("Data direction in, in quick settings", dataIn,
                expected.activityIn);
        assertEquals("Data direction out, in quick settings", dataOut,
                expected.activityOut);
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
        ArgumentCaptor<MobileDataIndicators> indicatorsArg =
                ArgumentCaptor.forClass(MobileDataIndicators.class);

        // TODO: Verify all fields.
        verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                indicatorsArg.capture());
        MobileDataIndicators expected = indicatorsArg.getValue();
        int state = icon == -1 ? 0
                : SignalDrawable.getState(icon, CellSignalStrength.getNumSignalStrengthLevels(),
                        !inet);
        assertEquals("Signal icon in status bar", state, expected.statusIcon.icon);
        assertEquals("Data icon in status bar", typeIcon, expected.statusType);
        assertEquals("Visibility in status bar", visible, expected.statusIcon.visible);
    }

    protected void verifyLastMobileDataIndicatorsForVcn(boolean visible, int level, int typeIcon,
            boolean inet) {
        ArgumentCaptor<MobileDataIndicators> indicatorsArg =
                ArgumentCaptor.forClass(MobileDataIndicators.class);

        verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                indicatorsArg.capture());

        MobileDataIndicators expected = indicatorsArg.getValue();
        int state = SignalDrawable.getState(
                level, CellSignalStrength.getNumSignalStrengthLevels(), !inet);
        assertEquals("Signal icon in status bar", state, expected.statusIcon.icon);
        assertEquals("Data icon in status bar", typeIcon, expected.statusType);
        assertEquals("Visibility in status bar", visible, expected.statusIcon.visible);
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
                null, null, visible);
    }

    protected void verifyLastMobileDataIndicators(boolean visible, int icon, int typeIcon,
            boolean qsVisible, int qsIcon, int qsTypeIcon, boolean dataIn, boolean dataOut,
            boolean cutOut, CharSequence typeContentDescription,
            CharSequence typeContentDescriptionHtml, boolean showQs) {
        ArgumentCaptor<MobileDataIndicators> indicatorsArg =
                ArgumentCaptor.forClass(MobileDataIndicators.class);
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> typeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<IconState> qsIconArg = ArgumentCaptor.forClass(IconState.class);
        ArgumentCaptor<Integer> qsTypeIconArg = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> dataInArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> dataOutArg = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<CharSequence> typeContentDescriptionArg =
                ArgumentCaptor.forClass(CharSequence.class);
        ArgumentCaptor<CharSequence> typeContentDescriptionHtmlArg =
                ArgumentCaptor.forClass(CharSequence.class);

        verify(mCallbackHandler, Mockito.atLeastOnce()).setMobileDataIndicators(
                indicatorsArg.capture());

        MobileDataIndicators expected = indicatorsArg.getValue();

        int numSignalStrengthBins = CellSignalStrength.getNumSignalStrengthLevels();
        if (mMobileSignalController.mInflateSignalStrengths) {
            numSignalStrengthBins++;
            icon++;
        }
        int state = SignalDrawable.getState(icon, numSignalStrengthBins, cutOut);
        assertEquals("Data icon in status bar", typeIcon, expected.statusType);
        assertEquals("Signal icon in status bar", state, expected.statusIcon.icon);
        assertEquals("Visibility in status bar", visible, expected.statusIcon.visible);

        if (showQs) {
            assertEquals("Visibility in quick settings", qsVisible, expected.qsIcon.visible);
            assertEquals("Signal icon in quick settings", state, expected.qsIcon.icon);
        } else {
            assertEquals("Cellular is not default", null, expected.qsIcon);
        }
        assertEquals("Data icon in quick settings", qsTypeIcon, expected.qsType);
        assertEquals("Data direction in in quick settings", dataIn,
                expected.activityIn);
        assertEquals("Data direction out in quick settings", dataOut,
                expected.activityOut);
        if (typeContentDescription != null) { // Only check if it was provided
            assertEquals("Type content description", typeContentDescription,
                    expected.typeContentDescription);
        }
        if (typeContentDescriptionHtml != null) { // Only check if it was provided
            assertEquals("Type content description (html)", typeContentDescriptionHtml,
                    expected.typeContentDescriptionHtml);
        }
    }

    protected void verifyLastCallStrength(int icon) {
        ArgumentCaptor<IconState> iconArg = ArgumentCaptor.forClass(IconState.class);
        verify(mCallbackHandler, Mockito.atLeastOnce()).setCallIndicator(
                iconArg.capture(),
                anyInt());
        assertEquals("Call strength, in status bar", icon, (int) iconArg.getValue().icon);
    }

    protected void assertNetworkNameEquals(String expected) {
        assertEquals("Network name", expected, mMobileSignalController.getState().networkName);
    }

    protected void assertDataNetworkNameEquals(String expected) {
        assertEquals("Data network name", expected, mNetworkController.getMobileDataNetworkName());
    }
}

