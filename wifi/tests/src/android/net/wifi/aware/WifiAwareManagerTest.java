/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.aware;

import static android.net.wifi.aware.WifiAwareNetworkSpecifier.NETWORK_SPECIFIER_TYPE_IB;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.RttManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Unit test harness for WifiAwareManager class.
 */
@SmallTest
public class WifiAwareManagerTest {
    private WifiAwareManager mDut;
    private TestLooper mMockLooper;
    private Handler mMockLooperHandler;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Mock
    public Context mockContext;

    @Mock
    public AttachCallback mockCallback;

    @Mock
    public DiscoverySessionCallback mockSessionCallback;

    @Mock
    public IWifiAwareManager mockAwareService;

    @Mock
    public PublishDiscoverySession mockPublishSession;

    @Mock
    public SubscribeDiscoverySession mockSubscribeSession;

    @Mock
    public RttManager.RttListener mockRttListener;

    @Mock
    public PackageManager mockPackageManager;

    @Mock
    public ApplicationInfo mockApplicationInfo;

    private static final int AWARE_STATUS_ERROR = -1;

    private static final byte[] PMK_VALID = "01234567890123456789012345678901".getBytes();
    private static final byte[] PMK_INVALID = "012".getBytes();

    private static final String PASSPHRASE_VALID = "SomeLongEnoughPassphrase";
    private static final String PASSPHRASE_TOO_SHORT = "012";
    private static final String PASSPHRASE_TOO_LONG =
            "0123456789012345678901234567890123456789012345678901234567890123456789";

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        when(mockPackageManager.getApplicationInfo(anyString(), anyInt())).thenReturn(
                mockApplicationInfo);
        when(mockContext.getOpPackageName()).thenReturn("XXX");
        when(mockContext.getPackageManager()).thenReturn(mockPackageManager);

        mDut = new WifiAwareManager(mockContext, mockAwareService);
        mMockLooper = new TestLooper();
        mMockLooperHandler = new Handler(mMockLooper.getLooper());
    }

    /*
     * Straight pass-through tests
     */

    /**
     * Validate pass-through of isUsageEnabled() API.
     */
    @Test
    public void testIsUsageEnable() throws Exception {
        mDut.isAvailable();

        verify(mockAwareService).isUsageEnabled();
    }

    /**
     * Validate pass-through of getCharacteristics() API.
     */
    @Test
    public void testGetCharacteristics() throws Exception {
        mDut.getCharacteristics();

        verify(mockAwareService).getCharacteristics();
    }

    /*
     * WifiAwareEventCallbackProxy Tests
     */

    /**
     * Validate the successful connect flow: (1) connect + success (2) publish, (3) disconnect
     * (4) try publishing on old session (5) connect again
     */
    @Test
    public void testConnectFlow() throws Exception {
        final int clientId = 4565;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IBinder> binder = ArgumentCaptor.forClass(IBinder.class);

        // (1) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(binder.capture(), any(),
                clientProxyCallback.capture(), isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish - should succeed
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(any(), eq(clientId), eq(publishConfig), any());

        // (3) disconnect
        session.close();
        inOrder.verify(mockAwareService).disconnect(eq(clientId), eq(binder.getValue()));

        // (4) try publishing again - fails silently
        session.publish(new PublishConfig.Builder().build(), mockSessionCallback,
                mMockLooperHandler);

        // (5) connect
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(binder.capture(), any(), any(), isNull(),
                eq(false));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService);
    }

    /**
     * Validate the failed connect flow: (1) connect + failure, (2) connect + success (3) subscribe
     */
    @Test
    public void testConnectFailure() throws Exception {
        final int clientId = 4565;
        final int reason = AWARE_STATUS_ERROR;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        // (1) connect + failure
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttachFailed();

        // (2) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (4) subscribe: should succeed
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).subscribe(any(), eq(clientId), eq(subscribeConfig), any());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService);
    }

    /**
     * Validate that can call connect to create multiple sessions: (1) connect
     * + success, (2) try connect again
     */
    @Test
    public void testInvalidConnectSequence() throws Exception {
        final int clientId = 4565;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        // (1) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(any());

        // (2) connect + success
        mDut.attach(mockCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                isNull(), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId + 1);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(any());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService);
    }

    /*
     * WifiAwareDiscoverySessionCallbackProxy Tests
     */

    /**
     * Validate the publish flow: (0) connect + success, (1) publish, (2)
     * success creates session, (3) pass through everything, (4) update publish
     * through session, (5) terminate locally, (6) try another command -
     * ignored.
     */
    @Test
    public void testPublishFlow() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();
        final PeerHandle peerHandle = new PeerHandle(873);
        final String string1 = "hey from here...";
        final byte[] matchFilter = { 1, 12, 2, 31, 32 };
        final int messageId = 2123;
        final int reason = AWARE_STATUS_ERROR;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);
        ArgumentCaptor<PeerHandle> peerIdCaptor = ArgumentCaptor.forClass(PeerHandle.class);
        ArgumentCaptor<List<byte[]>> matchFilterCaptor = ArgumentCaptor.forClass(
                (Class) List.class);

        // (0) connect + success
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (1) publish
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(any(), eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());

        // (2) publish session created
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) ...
        publishSession.getValue().sendMessage(peerHandle, messageId, string1.getBytes());
        sessionProxyCallback.getValue().onMatch(peerHandle.peerId, string1.getBytes(), matchFilter);
        sessionProxyCallback.getValue().onMessageReceived(peerHandle.peerId, string1.getBytes());
        sessionProxyCallback.getValue().onMessageSendFail(messageId, reason);
        sessionProxyCallback.getValue().onMessageSendSuccess(messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockAwareService).sendMessage(eq(clientId), eq(sessionId),
                eq(peerHandle.peerId), eq(string1.getBytes()), eq(messageId), eq(0));
        inOrder.verify(mockSessionCallback).onServiceDiscovered(peerIdCaptor.capture(),
                eq(string1.getBytes()),
                matchFilterCaptor.capture());

        // note: need to capture/compare elements since the Mockito eq() is a shallow comparator
        List<byte[]> parsedMatchFilter = new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList();
        collector.checkThat("match-filter-size", parsedMatchFilter.size(),
                equalTo(matchFilterCaptor.getValue().size()));
        collector.checkThat("match-filter-entry0", parsedMatchFilter.get(0),
                equalTo(matchFilterCaptor.getValue().get(0)));
        collector.checkThat("match-filter-entry1", parsedMatchFilter.get(1),
                equalTo(matchFilterCaptor.getValue().get(1)));

        assertEquals(peerIdCaptor.getValue().peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerIdCaptor.capture(),
                eq(string1.getBytes()));
        assertEquals(peerIdCaptor.getValue().peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageSendFailed(eq(messageId));
        inOrder.verify(mockSessionCallback).onMessageSendSucceeded(eq(messageId));

        // (4) update publish
        publishSession.getValue().updatePublish(publishConfig);
        sessionProxyCallback.getValue().onSessionConfigFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).updatePublish(eq(clientId), eq(sessionId),
                eq(publishConfig));
        inOrder.verify(mockSessionCallback).onSessionConfigFailed();

        // (5) terminate
        publishSession.getValue().close();
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).terminateSession(clientId, sessionId);

        // (6) try an update (nothing)
        publishSession.getValue().updatePublish(publishConfig);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
    }

    /**
     * Validate race condition of session terminate and session action: (1)
     * connect, (2) publish success + terminate, (3) update.
     */
    @Test
    public void testPublishRemoteTerminate() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish: successfully - then terminated
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(any(), eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        sessionProxyCallback.getValue().onSessionTerminated(0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());
        inOrder.verify(mockSessionCallback).onSessionTerminated();

        // (3) failure when trying to update: NOP
        publishSession.getValue().updatePublish(publishConfig);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession);
    }

    /**
     * Validate the subscribe flow: (0) connect + success, (1) subscribe, (2)
     * success creates session, (3) pass through everything, (4) update
     * subscribe through session, (5) terminate locally, (6) try another command
     * - ignored.
     */
    @Test
    public void testSubscribeFlow() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        final PeerHandle peerHandle = new PeerHandle(873);
        final String string1 = "hey from here...";
        final byte[] matchFilter = { 1, 12, 3, 31, 32 }; // bad data!
        final int messageId = 2123;
        final int reason = AWARE_STATUS_ERROR;
        final int distanceMm = 100;

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<SubscribeDiscoverySession> subscribeSession = ArgumentCaptor
                .forClass(SubscribeDiscoverySession.class);
        ArgumentCaptor<PeerHandle> peerIdCaptor = ArgumentCaptor.forClass(PeerHandle.class);

        // (0) connect + success
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (1) subscribe
        session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).subscribe(any(), eq(clientId), eq(subscribeConfig),
                sessionProxyCallback.capture());

        // (2) subscribe session created
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());

        // (3) ...
        subscribeSession.getValue().sendMessage(peerHandle, messageId, string1.getBytes());
        sessionProxyCallback.getValue().onMatch(peerHandle.peerId, string1.getBytes(), matchFilter);
        sessionProxyCallback.getValue().onMatchWithDistance(peerHandle.peerId, string1.getBytes(),
                matchFilter, distanceMm);
        sessionProxyCallback.getValue().onMessageReceived(peerHandle.peerId, string1.getBytes());
        sessionProxyCallback.getValue().onMessageSendFail(messageId, reason);
        sessionProxyCallback.getValue().onMessageSendSuccess(messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockAwareService).sendMessage(eq(clientId), eq(sessionId),
                eq(peerHandle.peerId), eq(string1.getBytes()), eq(messageId), eq(0));
        inOrder.verify(mockSessionCallback).onServiceDiscovered(peerIdCaptor.capture(),
                eq(string1.getBytes()), isNull());
        inOrder.verify(mockSessionCallback).onServiceDiscoveredWithinRange(peerIdCaptor.capture(),
                eq(string1.getBytes()), isNull(), eq(distanceMm));
        assertEquals((peerIdCaptor.getValue()).peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerIdCaptor.capture(),
                eq(string1.getBytes()));
        assertEquals((peerIdCaptor.getValue()).peerId, peerHandle.peerId);
        inOrder.verify(mockSessionCallback).onMessageSendFailed(eq(messageId));
        inOrder.verify(mockSessionCallback).onMessageSendSucceeded(eq(messageId));

        // (4) update subscribe
        subscribeSession.getValue().updateSubscribe(subscribeConfig);
        sessionProxyCallback.getValue().onSessionConfigFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).updateSubscribe(eq(clientId), eq(sessionId),
                eq(subscribeConfig));
        inOrder.verify(mockSessionCallback).onSessionConfigFailed();

        // (5) terminate
        subscribeSession.getValue().close();
        mMockLooper.dispatchAll();
        inOrder.verify(mockAwareService).terminateSession(clientId, sessionId);

        // (6) try an update (nothing)
        subscribeSession.getValue().updateSubscribe(subscribeConfig);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
    }

    /**
     * Validate race condition of session terminate and session action: (1)
     * connect, (2) subscribe success + terminate, (3) update.
     */
    @Test
    public void testSubscribeRemoteTerminate() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<SubscribeDiscoverySession> subscribeSession = ArgumentCaptor
                .forClass(SubscribeDiscoverySession.class);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) subscribe: successfully - then terminated
        session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).subscribe(any(), eq(clientId), eq(subscribeConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        sessionProxyCallback.getValue().onSessionTerminated(0);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());
        inOrder.verify(mockSessionCallback).onSessionTerminated();

        // (3) failure when trying to update: NOP
        subscribeSession.getValue().updateSubscribe(subscribeConfig);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockSubscribeSession);
    }

    /*
     * ConfigRequest Tests
     */

    @Test
    public void testConfigRequestBuilderDefaults() {
        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        collector.checkThat("mClusterHigh", ConfigRequest.CLUSTER_ID_MAX,
                equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", ConfigRequest.CLUSTER_ID_MIN,
                equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", 0,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", false, equalTo(configRequest.mSupport5gBand));
        collector.checkThat("mDiscoveryWindowInterval.length", 2,
                equalTo(configRequest.mDiscoveryWindowInterval.length));
        collector.checkThat("mDiscoveryWindowInterval[2.4GHz]", ConfigRequest.DW_INTERVAL_NOT_INIT,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]));
        collector.checkThat("mDiscoveryWindowInterval[5Hz]", ConfigRequest.DW_INTERVAL_NOT_INIT,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]));
    }

    @Test
    public void testConfigRequestBuilder() {
        final int clusterHigh = 100;
        final int clusterLow = 5;
        final int masterPreference = 55;
        final boolean supportBand5g = true;
        final int dwWindow5GHz = 3;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g)
                .setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_5GHZ, dwWindow5GHz)
                .build();

        collector.checkThat("mClusterHigh", clusterHigh, equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", clusterLow, equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", masterPreference,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", supportBand5g, equalTo(configRequest.mSupport5gBand));
        collector.checkThat("mDiscoveryWindowInterval.length", 2,
                equalTo(configRequest.mDiscoveryWindowInterval.length));
        collector.checkThat("mDiscoveryWindowInterval[2.4GHz]", ConfigRequest.DW_INTERVAL_NOT_INIT,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]));
        collector.checkThat("mDiscoveryWindowInterval[5GHz]", dwWindow5GHz,
                equalTo(configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefNegative() {
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefReserved1() {
        new ConfigRequest.Builder().setMasterPreference(1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefReserved255() {
        new ConfigRequest.Builder().setMasterPreference(255);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefTooLarge() {
        new ConfigRequest.Builder().setMasterPreference(256);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowNegative() {
        new ConfigRequest.Builder().setClusterLow(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterHighNegative() {
        new ConfigRequest.Builder().setClusterHigh(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowAboveMax() {
        new ConfigRequest.Builder().setClusterLow(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterHighAboveMax() {
        new ConfigRequest.Builder().setClusterHigh(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowLargerThanHigh() {
        new ConfigRequest.Builder().setClusterLow(100).setClusterHigh(5).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidBand() {
        new ConfigRequest.Builder().setDiscoveryWindowInterval(5, 1).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidValueZero() {
        new ConfigRequest.Builder().setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_24GHZ,
                0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidValueLarge() {
        new ConfigRequest.Builder().setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_5GHZ,
                6).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderDwIntervalInvalidValueLargeValidate() {
        ConfigRequest cr = new ConfigRequest.Builder().build();
        cr.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ] = 6;
        cr.validate();
    }

    @Test
    public void testConfigRequestParcel() {
        final int clusterHigh = 189;
        final int clusterLow = 25;
        final int masterPreference = 177;
        final boolean supportBand5g = true;
        final int dwWindow24GHz = 1;
        final int dwWindow5GHz = 5;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g)
                .setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_24GHZ, dwWindow24GHz)
                .setDiscoveryWindowInterval(ConfigRequest.NAN_BAND_5GHZ, dwWindow5GHz)
                .build();

        Parcel parcelW = Parcel.obtain();
        configRequest.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ConfigRequest rereadConfigRequest = ConfigRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(configRequest, rereadConfigRequest);
    }

    /*
     * SubscribeConfig Tests
     */

    @Test
    public void testSubscribeConfigBuilderDefaults() {
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        collector.checkThat("mServiceName", subscribeConfig.mServiceName, equalTo(null));
        collector.checkThat("mServiceSpecificInfo", subscribeConfig.mServiceSpecificInfo,
                equalTo(null));
        collector.checkThat("mMatchFilter", subscribeConfig.mMatchFilter, equalTo(null));
        collector.checkThat("mSubscribeType", subscribeConfig.mSubscribeType,
                equalTo(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE));
        collector.checkThat("mTtlSec", subscribeConfig.mTtlSec, equalTo(0));
        collector.checkThat("mEnableTerminateNotification",
                subscribeConfig.mEnableTerminateNotification, equalTo(true));
        collector.checkThat("mMinDistanceCmSet", subscribeConfig.mMinDistanceMmSet, equalTo(false));
        collector.checkThat("mMinDistanceMm", subscribeConfig.mMinDistanceMm, equalTo(0));
        collector.checkThat("mMaxDistanceMmSet", subscribeConfig.mMaxDistanceMmSet, equalTo(false));
        collector.checkThat("mMaxDistanceMm", subscribeConfig.mMaxDistanceMm, equalTo(0));
    }

    @Test
    public void testSubscribeConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeTtl = 15;
        final boolean enableTerminateNotification = false;
        final int minDistance = 10;
        final int maxDistance = 50;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                    new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setSubscribeType(subscribeType)
                .setTtlSec(subscribeTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification)
                .setMinDistanceMm(minDistance)
                .setMaxDistanceMm(maxDistance).build();

        collector.checkThat("mServiceName", serviceName.getBytes(),
                equalTo(subscribeConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                serviceSpecificInfo.getBytes(), equalTo(subscribeConfig.mServiceSpecificInfo));
        collector.checkThat("mMatchFilter", matchFilter, equalTo(subscribeConfig.mMatchFilter));
        collector.checkThat("mSubscribeType", subscribeType,
                equalTo(subscribeConfig.mSubscribeType));
        collector.checkThat("mTtlSec", subscribeTtl, equalTo(subscribeConfig.mTtlSec));
        collector.checkThat("mEnableTerminateNotification", enableTerminateNotification,
                equalTo(subscribeConfig.mEnableTerminateNotification));
        collector.checkThat("mMinDistanceMmSet", true, equalTo(subscribeConfig.mMinDistanceMmSet));
        collector.checkThat("mMinDistanceMm", minDistance, equalTo(subscribeConfig.mMinDistanceMm));
        collector.checkThat("mMaxDistanceMmSet", true, equalTo(subscribeConfig.mMaxDistanceMmSet));
        collector.checkThat("mMaxDistanceMm", maxDistance, equalTo(subscribeConfig.mMaxDistanceMm));
    }

    @Test
    public void testSubscribeConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeTtl = 15;
        final boolean enableTerminateNotification = true;
        final int minDistance = 10;
        final int maxDistance = 50;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setSubscribeType(subscribeType)
                .setTtlSec(subscribeTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification)
                .setMinDistanceMm(minDistance)
                .setMaxDistanceMm(maxDistance).build();

        Parcel parcelW = Parcel.obtain();
        subscribeConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SubscribeConfig rereadSubscribeConfig = SubscribeConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(subscribeConfig, rereadSubscribeConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderBadSubscribeType() {
        new SubscribeConfig.Builder().setSubscribeType(10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderNegativeTtl() {
        new SubscribeConfig.Builder().setTtlSec(-100);
    }

    /*
     * PublishConfig Tests
     */

    @Test
    public void testPublishConfigBuilderDefaults() {
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        collector.checkThat("mServiceName", publishConfig.mServiceName, equalTo(null));
        collector.checkThat("mServiceSpecificInfo", publishConfig.mServiceSpecificInfo,
                equalTo(null));
        collector.checkThat("mMatchFilter", publishConfig.mMatchFilter, equalTo(null));
        collector.checkThat("mPublishType", publishConfig.mPublishType,
                equalTo(PublishConfig.PUBLISH_TYPE_UNSOLICITED));
        collector.checkThat("mTtlSec", publishConfig.mTtlSec, equalTo(0));
        collector.checkThat("mEnableTerminateNotification",
                publishConfig.mEnableTerminateNotification, equalTo(true));
        collector.checkThat("mEnableRanging", publishConfig.mEnableRanging, equalTo(false));
    }

    @Test
    public void testPublishConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishTtl = 15;
        final boolean enableTerminateNotification = false;
        final boolean enableRanging = true;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setPublishType(publishType)
                .setTtlSec(publishTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification)
                .setRangingEnabled(enableRanging).build();

        collector.checkThat("mServiceName", serviceName.getBytes(),
                equalTo(publishConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                serviceSpecificInfo.getBytes(), equalTo(publishConfig.mServiceSpecificInfo));
        collector.checkThat("mMatchFilter", matchFilter, equalTo(publishConfig.mMatchFilter));
        collector.checkThat("mPublishType", publishType, equalTo(publishConfig.mPublishType));
        collector.checkThat("mTtlSec", publishTtl, equalTo(publishConfig.mTtlSec));
        collector.checkThat("mEnableTerminateNotification", enableTerminateNotification,
                equalTo(publishConfig.mEnableTerminateNotification));
        collector.checkThat("mEnableRanging", enableRanging, equalTo(publishConfig.mEnableRanging));
    }

    @Test
    public void testPublishConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] matchFilter = { 1, 16, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishTtl = 15;
        final boolean enableTerminateNotification = false;
        final boolean enableRanging = true;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo.getBytes()).setMatchFilter(
                        new TlvBufferUtils.TlvIterable(0, 1, matchFilter).toList())
                .setPublishType(publishType)
                .setTtlSec(publishTtl)
                .setTerminateNotificationEnabled(enableTerminateNotification)
                .setRangingEnabled(enableRanging).build();

        Parcel parcelW = Parcel.obtain();
        publishConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        PublishConfig rereadPublishConfig = PublishConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(publishConfig, rereadPublishConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderBadPublishType() {
        new PublishConfig.Builder().setPublishType(5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderNegativeTtl() {
        new PublishConfig.Builder().setTtlSec(-10);
    }

    /*
     * Data-path tests
     */

    /**
     * Validate that correct network specifier is generated for client-based data-path.
     */
    @Test
    public void testNetworkSpecifierWithClient() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final PeerHandle peerHandle = new PeerHandle(123412);
        final byte[] pmk = PMK_VALID;
        final String passphrase = PASSPHRASE_VALID;
        final int port = 5;
        final int transportProtocol = 10;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        mockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.P;

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish successfully
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(any(), eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) request an open (unencrypted) network specifier from the session
        WifiAwareNetworkSpecifier ns =
                (WifiAwareNetworkSpecifier) publishSession.getValue().createNetworkSpecifierOpen(
                        peerHandle);
        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(
                publishSession.getValue(), peerHandle).build();

        // validate format
        collector.checkThat("role", WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER,
                equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("session_id", sessionId, equalTo(ns.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(ns.peerId));

        collector.checkThat("role", WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER,
                equalTo(nsb.role));
        collector.checkThat("client_id", clientId, equalTo(nsb.clientId));
        collector.checkThat("session_id", sessionId, equalTo(nsb.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(nsb.peerId));
        collector.checkThat("port", 0, equalTo(nsb.port));
        collector.checkThat("transportProtocol", -1, equalTo(nsb.transportProtocol));

        // (4) request an encrypted (PMK) network specifier from the session
        ns = (WifiAwareNetworkSpecifier) publishSession.getValue().createNetworkSpecifierPmk(
                peerHandle, pmk);
        nsb = new WifiAwareNetworkSpecifier.Builder(publishSession.getValue(), peerHandle).setPmk(
                pmk).setPort(port).setTransportProtocol(transportProtocol).build();

        // validate format
        collector.checkThat("role", WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER,
                equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("session_id", sessionId, equalTo(ns.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(ns.peerId));
        collector.checkThat("pmk", pmk , equalTo(ns.pmk));

        collector.checkThat("role", WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER,
                equalTo(nsb.role));
        collector.checkThat("client_id", clientId, equalTo(nsb.clientId));
        collector.checkThat("session_id", sessionId, equalTo(nsb.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(nsb.peerId));
        collector.checkThat("pmk", pmk , equalTo(nsb.pmk));
        collector.checkThat("port", port, equalTo(nsb.port));
        collector.checkThat("transportProtocol", transportProtocol, equalTo(nsb.transportProtocol));

        // (5) request an encrypted (Passphrase) network specifier from the session
        ns =
                (WifiAwareNetworkSpecifier) publishSession.getValue()
                        .createNetworkSpecifierPassphrase(
                        peerHandle, passphrase);
        nsb = new WifiAwareNetworkSpecifier.Builder(publishSession.getValue(),
                peerHandle).setPskPassphrase(passphrase).setPort(port).setTransportProtocol(
                transportProtocol).build();

        // validate format
        collector.checkThat("role", WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER,
                equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("session_id", sessionId, equalTo(ns.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(ns.peerId));
        collector.checkThat("passphrase", passphrase, equalTo(ns.passphrase));

        collector.checkThat("role", WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER,
                equalTo(nsb.role));
        collector.checkThat("client_id", clientId, equalTo(nsb.clientId));
        collector.checkThat("session_id", sessionId, equalTo(nsb.sessionId));
        collector.checkThat("peer_id", peerHandle.peerId, equalTo(nsb.peerId));
        collector.checkThat("passphrase", passphrase, equalTo(nsb.passphrase));
        collector.checkThat("port", port, equalTo(nsb.port));
        collector.checkThat("transportProtocol", transportProtocol, equalTo(nsb.transportProtocol));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);
    }

    /**
     * Validate that correct network specifier is generated for a direct data-path (i.e.
     * specifying MAC address as opposed to a client-based oqaque specification).
     */
    @Test
    public void testNetworkSpecifierDirect() throws Exception {
        final int clientId = 134;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);
        final int role = WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR;
        final byte[] pmk = PMK_VALID;
        final String passphrase = PASSPHRASE_VALID;

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) request an open (unencrypted) direct network specifier
        WifiAwareNetworkSpecifier ns =
                (WifiAwareNetworkSpecifier) session.createNetworkSpecifierOpen(role, someMac);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("peer_mac", someMac, equalTo(ns.peerMac));

        // (3) request an encrypted (PMK) direct network specifier
        ns = (WifiAwareNetworkSpecifier) session.createNetworkSpecifierPmk(role, someMac, pmk);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("peer_mac", someMac, equalTo(ns.peerMac));
        collector.checkThat("pmk", pmk, equalTo(ns.pmk));

        // (4) request an encrypted (Passphrase) direct network specifier
        ns = (WifiAwareNetworkSpecifier) session.createNetworkSpecifierPassphrase(role, someMac,
                passphrase);

        // validate format
        collector.checkThat("role", role, equalTo(ns.role));
        collector.checkThat("client_id", clientId, equalTo(ns.clientId));
        collector.checkThat("peer_mac", someMac, equalTo(ns.peerMac));
        collector.checkThat("passphrase", passphrase, equalTo(ns.passphrase));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);
    }

    /**
     * Validate that a null PMK triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientNullPmk() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), true, null, null, false);
    }

    /**
     * Validate that a non-32-bytes PMK triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientIncorrectLengthPmk() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), true, PMK_INVALID, null, false);
    }

    /**
     * Validate that a null Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientNullPassphrase() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), false, null, null, false);
    }

    /**
     * Validate that a too short Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientTooShortPassphrase() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), false, null,
                PASSPHRASE_TOO_SHORT, false);
    }

    /**
     * Validate that a too long Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientTooLongPassphrase() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), false, null, PASSPHRASE_TOO_LONG,
                false);
    }

    /**
     * Validate that a null PeerHandle triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientNullPeer() throws Exception {
        mockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
        executeNetworkSpecifierWithClient(null, false, null, PASSPHRASE_VALID, false);
    }

    /**
     * Validate that a null PeerHandle does not trigger an exception for legacy API.
     */
    @Test
    public void testNetworkSpecifierWithClientNullPeerLegacyApi() throws Exception {
        mockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.O;
        executeNetworkSpecifierWithClient(null, false, null, PASSPHRASE_VALID, false);
    }

    /**
     * Validate that a null PMK triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientNullPmkBuilder() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), true, null, null, true);
    }

    /**
     * Validate that a non-32-bytes PMK triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientIncorrectLengthPmkBuilder() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), true, PMK_INVALID, null, true);
    }

    /**
     * Validate that a null Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientNullPassphraseBuilder() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), false, null, null, true);
    }

    /**
     * Validate that a too short Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientTooShortPassphraseBuilder() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), false, null,
                PASSPHRASE_TOO_SHORT, true);
    }

    /**
     * Validate that a too long Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientTooLongPassphraseBuilder() throws Exception {
        executeNetworkSpecifierWithClient(new PeerHandle(123412), false, null, PASSPHRASE_TOO_LONG,
                true);
    }

    /**
     * Validate that a null PeerHandle triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierWithClientNullPeerBuilder() throws Exception {
        executeNetworkSpecifierWithClient(null, false, null, PASSPHRASE_VALID, true);
    }

    /**
     * Validate that a null PeerHandle does not trigger an exception for legacy API.
     */
    @Test
    public void testNetworkSpecifierWithClientNullPeerLegacyApiBuilder() throws Exception {
        mockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.O;
        executeNetworkSpecifierWithClient(null, false, null, PASSPHRASE_VALID, false);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testNetworkSpecifierDeprecatedOnNewApi() throws Exception {
        executeNetworkSpecifierWithClient(null, false, null, PASSPHRASE_VALID, false);
    }

    private void executeNetworkSpecifierWithClient(PeerHandle peerHandle, boolean doPmk, byte[] pmk,
            String passphrase, boolean useBuilder) throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        // (2) publish successfully
        session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
        inOrder.verify(mockAwareService).publish(any(), eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) create network specifier
        if (doPmk) {
            if (useBuilder) {
                new WifiAwareNetworkSpecifier.Builder(publishSession.getValue(), peerHandle).setPmk(
                        pmk).build();
            } else {
                publishSession.getValue().createNetworkSpecifierPmk(peerHandle, pmk);
            }
        } else {
            if (useBuilder) {
                new WifiAwareNetworkSpecifier.Builder(publishSession.getValue(),
                        peerHandle).setPskPassphrase(passphrase).build();
            } else {
                publishSession.getValue().createNetworkSpecifierPassphrase(peerHandle, passphrase);
            }
        }
    }

    /**
     * Validate that a null PMK triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectNullPmk() throws Exception {
        executeNetworkSpecifierDirect(HexEncoding.decode("000102030405".toCharArray(), false), true,
                null, null, true);
    }

    /**
     * Validate that a non-32-bytes PMK triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectIncorrectLengthPmk() throws Exception {
        executeNetworkSpecifierDirect(HexEncoding.decode("000102030405".toCharArray(), false), true,
                PMK_INVALID, null, true);
    }

    /**
     * Validate that a null Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectNullPassphrase() throws Exception {
        executeNetworkSpecifierDirect(HexEncoding.decode("000102030405".toCharArray(), false),
                false, null, null, true);
    }

    /**
     * Validate that a too short Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectTooShortPassphrase() throws Exception {
        executeNetworkSpecifierDirect(HexEncoding.decode("000102030405".toCharArray(), false),
                false, null, PASSPHRASE_TOO_SHORT, true);
    }

    /**
     * Validate that a too long Passphrase triggers an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectTooLongPassphrase() throws Exception {
        executeNetworkSpecifierDirect(HexEncoding.decode("000102030405".toCharArray(), false),
                false, null, PASSPHRASE_TOO_LONG, true);
    }

    /**
     * Validate that a null peer MAC triggers an exception for an Initiator.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectNullPeerInitiator() throws Exception {
        executeNetworkSpecifierDirect(null, false, null, PASSPHRASE_VALID, true);
    }

    /**
     * Validate that a null peer MAC triggers an exception for a Resonder.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierDirectNullPeerResponder() throws Exception {
        executeNetworkSpecifierDirect(null, false, null, PASSPHRASE_VALID, false);
    }

    /**
     * Validate that a null peer MAC does not trigger an exception for a Resonder on legacy API.
     */
    @Test
    public void testNetworkSpecifierDirectNullPeerResponderLegacyApi() throws Exception {
        mockApplicationInfo.targetSdkVersion = Build.VERSION_CODES.O;
        executeNetworkSpecifierDirect(null, false, null, PASSPHRASE_VALID, false);
    }

    /**
     * Validate that get an exception when creating a network specifier with an invalid port number
     * (<=0).
     */
    @Test(expected = IllegalArgumentException.class)
    public void testNetworkSpecifierBuilderInvalidPortNumber() throws Exception {
        final PeerHandle peerHandle = new PeerHandle(123412);
        final byte[] pmk = PMK_VALID;
        final int port = 0;

        DiscoverySession publishSession = executeSessionStartup(true);

        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(publishSession,
                peerHandle).setPmk(pmk).setPort(port).build();
    }

    /**
     * Validate that get an exception when creating a network specifier with port information
     * without also requesting a secure link.
     */
    @Test(expected = IllegalStateException.class)
    public void testNetworkSpecifierBuilderInvalidPortOnInsecure() throws Exception {
        final PeerHandle peerHandle = new PeerHandle(123412);
        final int port = 5;

        DiscoverySession publishSession = executeSessionStartup(true);

        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(publishSession,
                peerHandle).setPort(port).build();
    }

    /**
     * Validate that get an exception when creating a network specifier with port information on
     * a responder.
     */
    @Test(expected = IllegalStateException.class)
    public void testNetworkSpecifierBuilderInvalidPortOnResponder() throws Exception {
        final PeerHandle peerHandle = new PeerHandle(123412);
        final int port = 5;

        DiscoverySession subscribeSession = executeSessionStartup(false);

        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(subscribeSession,
                peerHandle).setPort(port).build();
    }

    /**
     * Validate that get an exception when creating a network specifier with an invalid transport
     * protocol number (not in [0, 255]).
     */
    @Test
    public void testNetworkSpecifierBuilderInvalidTransportProtocolNumber() throws Exception {
        final PeerHandle peerHandle = new PeerHandle(123412);
        final byte[] pmk = PMK_VALID;
        final int tpNegative = -1;
        final int tpTooLarge = 256;
        final int tpSmallest = 0;
        final int tpLargest = 255;

        DiscoverySession publishSession = executeSessionStartup(true);

        try {
            WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(publishSession,
                    peerHandle).setPmk(pmk).setTransportProtocol(tpNegative).build();
            assertTrue("No exception on negative transport protocol!", false);
        } catch (IllegalArgumentException e) {
            // nop - exception is correct!
        }
        try {
            WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(publishSession,
                    peerHandle).setPmk(pmk).setTransportProtocol(tpTooLarge).build();
            assertTrue("No exception on >255 transport protocol!", false);
        } catch (IllegalArgumentException e) {
            // nop - exception is correct!
        }
        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(publishSession,
                peerHandle).setPmk(pmk).setTransportProtocol(tpSmallest).build();
        nsb = new WifiAwareNetworkSpecifier.Builder(publishSession, peerHandle).setPmk(
                pmk).setTransportProtocol(tpLargest).build();
    }

    /**
     * Validate that get an exception when creating a network specifier with transport protocol
     * information without also requesting a secure link.
     */
    @Test(expected = IllegalStateException.class)
    public void testNetworkSpecifierBuilderInvalidTransportProtocolOnInsecure() throws Exception {
        final PeerHandle peerHandle = new PeerHandle(123412);
        final int transportProtocol = 5;

        DiscoverySession publishSession = executeSessionStartup(true);

        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(publishSession,
                peerHandle).setTransportProtocol(transportProtocol).build();
    }

    /**
     * Validate that get an exception when creating a network specifier with transport protocol
     * information on a responder.
     */
    @Test(expected = IllegalStateException.class)
    public void testNetworkSpecifierBuilderInvalidTransportProtocolOnResponder() throws Exception {
        final PeerHandle peerHandle = new PeerHandle(123412);
        final int transportProtocol = 5;

        DiscoverySession subscribeSession = executeSessionStartup(false);

        WifiAwareNetworkSpecifier nsb = new WifiAwareNetworkSpecifier.Builder(subscribeSession,
                peerHandle).setTransportProtocol(transportProtocol).build();
    }

    /*
     * Utilities
     */

    private void executeNetworkSpecifierDirect(byte[] someMac, boolean doPmk, byte[] pmk,
            String passphrase, boolean doInitiator) throws Exception {
        final int clientId = 134;
        final int role = doInitiator ? WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR
                : WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());

        // (2) create network specifier
        if (doPmk) {
            sessionCaptor.getValue().createNetworkSpecifierPmk(role, someMac, pmk);
        } else {
            sessionCaptor.getValue().createNetworkSpecifierPassphrase(role, someMac, passphrase);
        }
    }

    private DiscoverySession executeSessionStartup(boolean isPublish) throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final PeerHandle peerHandle = new PeerHandle(123412);
        final int port = 5;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<WifiAwareSession> sessionCaptor = ArgumentCaptor.forClass(
                WifiAwareSession.class);
        ArgumentCaptor<IWifiAwareEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareEventCallback.class);
        ArgumentCaptor<IWifiAwareDiscoverySessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiAwareDiscoverySessionCallback.class);
        ArgumentCaptor<PublishDiscoverySession> publishSession = ArgumentCaptor
                .forClass(PublishDiscoverySession.class);
        ArgumentCaptor<SubscribeDiscoverySession> subscribeSession = ArgumentCaptor
                .forClass(SubscribeDiscoverySession.class);


        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockAwareService,
                mockPublishSession, mockRttListener);

        // (1) connect successfully
        mDut.attach(mMockLooperHandler, configRequest, mockCallback, null);
        inOrder.verify(mockAwareService).connect(any(), any(), clientProxyCallback.capture(),
                eq(configRequest), eq(false));
        clientProxyCallback.getValue().onConnectSuccess(clientId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onAttached(sessionCaptor.capture());
        WifiAwareSession session = sessionCaptor.getValue();

        if (isPublish) {
            // (2) publish successfully
            session.publish(publishConfig, mockSessionCallback, mMockLooperHandler);
            inOrder.verify(mockAwareService).publish(any(), eq(clientId), eq(publishConfig),
                    sessionProxyCallback.capture());
            sessionProxyCallback.getValue().onSessionStarted(sessionId);
            mMockLooper.dispatchAll();
            inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());
            return publishSession.getValue();
        } else {
            // (2) subscribe successfully
            session.subscribe(subscribeConfig, mockSessionCallback, mMockLooperHandler);
            inOrder.verify(mockAwareService).subscribe(any(), eq(clientId), eq(subscribeConfig),
                    sessionProxyCallback.capture());
            sessionProxyCallback.getValue().onSessionStarted(sessionId);
            mMockLooper.dispatchAll();
            inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());
            return subscribeSession.getValue();
        }
    }

    // WifiAwareNetworkSpecifier && WifiAwareNetworkInfo tests

    @Test
    public void testWifiAwareNetworkSpecifierParcel() {
        WifiAwareNetworkSpecifier ns = new WifiAwareNetworkSpecifier(NETWORK_SPECIFIER_TYPE_IB,
                WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER, 5, 568, 334,
                HexEncoding.decode("000102030405".toCharArray(), false),
                "01234567890123456789012345678901".getBytes(), "blah blah", 666, 4, 10001);

        Parcel parcelW = Parcel.obtain();
        ns.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiAwareNetworkSpecifier rereadNs =
                WifiAwareNetworkSpecifier.CREATOR.createFromParcel(parcelR);

        assertEquals(ns, rereadNs);
        assertEquals(ns.hashCode(), rereadNs.hashCode());
    }

    @Test
    public void testWifiAwareNetworkCapabilitiesParcel() throws UnknownHostException {
        final Inet6Address inet6 = MacAddress.fromString(
                "11:22:33:44:55:66").getLinkLocalIpv6FromEui48Mac();
        // note: dummy scope = 5
        final Inet6Address inet6Scoped = Inet6Address.getByAddress(null, inet6.getAddress(), 5);
        final int port = 5;
        final int transportProtocol = 6;

        assertEquals(inet6Scoped.toString(), "/fe80::1322:33ff:fe44:5566%5");
        WifiAwareNetworkInfo cap = new WifiAwareNetworkInfo(inet6Scoped, port, transportProtocol);

        Parcel parcelW = Parcel.obtain();
        cap.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiAwareNetworkInfo rereadCap =
                WifiAwareNetworkInfo.CREATOR.createFromParcel(parcelR);

        assertEquals(cap.getPeerIpv6Addr().toString(), "/fe80::1322:33ff:fe44:5566%5");
        assertEquals(cap, rereadCap);
        assertEquals(cap.hashCode(), rereadCap.hashCode());
    }

    // ParcelablePeerHandle tests

    /**
     * Verify parceling of ParcelablePeerHandle and interoperability with PeerHandle.
     */
    @Test
    public void testParcelablePeerHandleParcel() {
        final PeerHandle peerHandle = new PeerHandle(5);
        final ParcelablePeerHandle parcelablePeerHandle = new ParcelablePeerHandle(peerHandle);

        Parcel parcelW = Parcel.obtain();
        parcelablePeerHandle.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ParcelablePeerHandle rereadParcelablePeerHandle =
                ParcelablePeerHandle.CREATOR.createFromParcel(parcelR);

        assertEquals(peerHandle, rereadParcelablePeerHandle);
        assertEquals(peerHandle.hashCode(), rereadParcelablePeerHandle.hashCode());
        assertEquals(parcelablePeerHandle, rereadParcelablePeerHandle);
        assertEquals(parcelablePeerHandle.hashCode(), rereadParcelablePeerHandle.hashCode());

    }
}
