/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.intrusiondetection;

import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.MANAGE_INTRUSION_DETECTION_STATE;
import static android.Manifest.permission.READ_INTRUSION_DETECTION_STATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.app.admin.ConnectEvent;
import android.app.admin.DnsEvent;
import android.app.admin.SecurityLog;
import android.app.admin.SecurityLog.SecurityEvent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.os.test.TestLooper;
import android.security.intrusiondetection.IIntrusionDetectionServiceCommandCallback;
import android.security.intrusiondetection.IIntrusionDetectionServiceStateCallback;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.test.core.app.ApplicationProvider;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.annotations.AfterClass;
import com.android.bedstead.harrier.annotations.BeforeClass;
import com.android.bedstead.multiuser.annotations.RequireRunOnSystemUser;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.devicepolicy.DeviceOwner;
import com.android.bedstead.nene.exceptions.NeneException;
import com.android.bedstead.permissions.CommonPermissions;
import com.android.bedstead.permissions.PermissionContext;
import com.android.bedstead.permissions.annotations.EnsureHasPermission;
import com.android.server.ServiceThread;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(BedsteadJUnit4.class)
public class IntrusionDetectionServiceTest {
    private static final int STATE_UNKNOWN =
            IIntrusionDetectionServiceStateCallback.State.UNKNOWN;
    private static final int STATE_DISABLED =
            IIntrusionDetectionServiceStateCallback.State.DISABLED;
    private static final int STATE_ENABLED =
            IIntrusionDetectionServiceStateCallback.State.ENABLED;

    private static final int ERROR_UNKNOWN =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.UNKNOWN;
    private static final int ERROR_PERMISSION_DENIED =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.PERMISSION_DENIED;
    private static final int ERROR_TRANSPORT_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.TRANSPORT_UNAVAILABLE;
    private static final int ERROR_DATA_SOURCE_UNAVAILABLE =
            IIntrusionDetectionServiceCommandCallback.ErrorCode.DATA_SOURCE_UNAVAILABLE;

    private static DeviceOwner sDeviceOwner;

    private Context mContext;
    private IntrusionDetectionEventTransportConnection mIntrusionDetectionEventTransportConnection;
    private DataAggregator mDataAggregator;
    private IntrusionDetectionService mIntrusionDetectionService;
    private TestLooper mTestLooper;
    private Looper mLooper;
    private TestLooper mTestLooperOfDataAggregator;
    private Looper mLooperOfDataAggregator;
    private FakePermissionEnforcer mPermissionEnforcer;

    @BeforeClass
    public static void setDeviceOwner() {
        ComponentName admin =
                new ComponentName(
                        ApplicationProvider.getApplicationContext(),
                        IntrusionDetectionAdminReceiver.class);
        try {
            sDeviceOwner = TestApis.devicePolicy().setDeviceOwner(admin);
        } catch (NeneException e) {
            fail("Failed to set device owner " + admin.flattenToString() + ": " + e);
        }
    }

    @AfterClass
    public static void removeDeviceOwner() {
        try {
            sDeviceOwner.remove();
        } catch (NeneException e) {
            fail("Failed to remove device owner : " + e);
        }
    }

    @SuppressLint("VisibleForTests")
    @Before
    public void setUp() {
        mContext = spy(ApplicationProvider.getApplicationContext());

        mPermissionEnforcer = new FakePermissionEnforcer();
        mPermissionEnforcer.grant(READ_INTRUSION_DETECTION_STATE);
        mPermissionEnforcer.grant(MANAGE_INTRUSION_DETECTION_STATE);

        mTestLooper = new TestLooper();
        mLooper = mTestLooper.getLooper();
        mTestLooperOfDataAggregator = new TestLooper();
        mLooperOfDataAggregator = mTestLooperOfDataAggregator.getLooper();
        mIntrusionDetectionService = new IntrusionDetectionService(new MockInjector(mContext));
        mIntrusionDetectionService.onStart();
    }

    @Test
    public void testAddStateCallback_NoPermission() {
        mPermissionEnforcer.revoke(READ_INTRUSION_DETECTION_STATE);
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().addStateCallback(scb));
    }

    @Test
    public void testRemoveStateCallback_NoPermission() {
        mPermissionEnforcer.revoke(READ_INTRUSION_DETECTION_STATE);
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().removeStateCallback(scb));
    }

    @Test
    public void testEnable_NoPermission() {
        mPermissionEnforcer.revoke(MANAGE_INTRUSION_DETECTION_STATE);

        CommandCallback ccb = new CommandCallback();
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().enable(ccb));
    }

    @Test
    public void testDisable_NoPermission() {
        mPermissionEnforcer.revoke(MANAGE_INTRUSION_DETECTION_STATE);

        CommandCallback ccb = new CommandCallback();
        assertThrows(SecurityException.class,
                () -> mIntrusionDetectionService.getBinderService().disable(ccb));
    }

    @Test
    public void testAddStateCallback_Disabled() throws RemoteException {
        StateCallback scb = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb.mState);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb.mState);
    }

    @Test
    public void testAddStateCallback_Disabled_TwoStateCallbacks() throws RemoteException {
        StateCallback scb1 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb1.mState);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);

        StateCallback scb2 = new StateCallback();
        assertEquals(STATE_UNKNOWN, scb2.mState);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb2.mState);
    }

    @Test
    public void testRemoveStateCallback() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(true).when(mDataAggregator).initialize();
        doReturn(true).when(mIntrusionDetectionEventTransportConnection).initialize();

        mIntrusionDetectionService.getBinderService().removeStateCallback(scb2);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromDisabled_TwoStateCallbacks() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(true).when(mIntrusionDetectionEventTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();

        verify(mDataAggregator, times(1)).enable();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testEnable_FromEnabled_TwoStateCallbacks()
            throws RemoteException {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();

        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromDisabled_TwoStateCallbacks() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();

        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Test
    public void testDisable_FromEnabled_TwoStateCallbacks() throws RemoteException {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_ENABLED, scb1.mState);
        assertEquals(STATE_ENABLED, scb2.mState);

        doNothing().when(mIntrusionDetectionEventTransportConnection).release();

        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().disable(ccb);
        mTestLooper.dispatchAll();
        mTestLooperOfDataAggregator.dispatchAll();
        // TODO: We can verify the data sources once we implement them.
        verify(mockThread, times(1)).quitSafely();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNull(ccb.mErrorCode);
    }

    @Ignore("Enable once the IntrusionDetectionEventTransportConnection is ready")
    @Test
    public void testEnable_FromDisable_TwoStateCallbacks_TransportUnavailable()
            throws RemoteException {
        mIntrusionDetectionService.setState(STATE_DISABLED);
        StateCallback scb1 = new StateCallback();
        StateCallback scb2 = new StateCallback();
        mIntrusionDetectionService.getBinderService().addStateCallback(scb1);
        mIntrusionDetectionService.getBinderService().addStateCallback(scb2);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);

        doReturn(false).when(mIntrusionDetectionEventTransportConnection).initialize();

        CommandCallback ccb = new CommandCallback();
        mIntrusionDetectionService.getBinderService().enable(ccb);
        mTestLooper.dispatchAll();
        assertEquals(STATE_DISABLED, scb1.mState);
        assertEquals(STATE_DISABLED, scb2.mState);
        assertNotNull(ccb.mErrorCode);
        assertEquals(ERROR_TRANSPORT_UNAVAILABLE, ccb.mErrorCode.intValue());
    }

    @Test
    public void testDataAggregator_AddBatchData() {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);

        SecurityEvent securityEvent = new SecurityEvent(0, new byte[0]);
        IntrusionDetectionEvent eventOne = new IntrusionDetectionEvent(securityEvent);

        ConnectEvent connectEvent = new ConnectEvent(
                "127.0.0.1", 80, null, 0);
        IntrusionDetectionEvent eventTwo = new IntrusionDetectionEvent(connectEvent);

        DnsEvent dnsEvent = new DnsEvent(
                null, new String[] {"127.0.0.1"}, 1, null, 0);
        IntrusionDetectionEvent eventThree = new IntrusionDetectionEvent(dnsEvent);

        List<IntrusionDetectionEvent> events = new ArrayList<>();
        events.add(eventOne);
        events.add(eventTwo);
        events.add(eventThree);

        doReturn(true).when(mIntrusionDetectionEventTransportConnection).addData(any());

        mDataAggregator.addBatchData(events);
        mTestLooperOfDataAggregator.dispatchAll();
        mTestLooper.dispatchAll();

        ArgumentCaptor<List<IntrusionDetectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(mIntrusionDetectionEventTransportConnection).addData(captor.capture());
        List<IntrusionDetectionEvent> receivedEvents = captor.getValue();
        assertEquals(receivedEvents.size(), 3);

        assertEquals(receivedEvents.get(0).getType(), IntrusionDetectionEvent.SECURITY_EVENT);
        assertNotNull(receivedEvents.get(0).getSecurityEvent());

        assertEquals(receivedEvents.get(1).getType(),
                IntrusionDetectionEvent.NETWORK_EVENT_CONNECT);
        assertNotNull(receivedEvents.get(1).getConnectEvent());

        assertEquals(receivedEvents.get(2).getType(), IntrusionDetectionEvent.NETWORK_EVENT_DNS);
        assertNotNull(receivedEvents.get(2).getDnsEvent());
    }

    @Test
    @RequireRunOnSystemUser
    public void testDataSources_Initialize_HasDeviceOwner() throws Exception {
        NetworkLogSource networkLogSource = new NetworkLogSource(mContext, mDataAggregator);
        SecurityLogSource securityLogSource = new SecurityLogSource(mContext, mDataAggregator);

        assertTrue(networkLogSource.initialize());
        assertTrue(securityLogSource.initialize());
    }

    @Test
    @RequireRunOnSystemUser
    public void testDataSources_Initialize_NoDeviceOwner() throws Exception {
        NetworkLogSource networkLogSource = new NetworkLogSource(mContext, mDataAggregator);
        SecurityLogSource securityLogSource = new SecurityLogSource(mContext, mDataAggregator);
        ComponentName admin = sDeviceOwner.componentName();

        try {
            sDeviceOwner.remove();
            assertFalse(networkLogSource.initialize());
            assertFalse(securityLogSource.initialize());
        } finally {
            sDeviceOwner = TestApis.devicePolicy().setDeviceOwner(admin);
        }
    }

    @Test
    @RequireRunOnSystemUser
    @EnsureHasPermission(CommonPermissions.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void testDataAggregator_AddSecurityEvent() throws Exception {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);
        assertTrue(mDataAggregator.initialize());

        // SecurityLogging generates a number of events and callbacks, so create a latch to wait for
        // the given event.
        String eventString = this.getClass().getName() + ".testSecurityEvent";

        final CountDownLatch latch = new CountDownLatch(1);
        // TODO: Replace this mock when the IntrusionDetectionEventTransportConnection is ready.
        doAnswer(
                    new Answer<Boolean>() {
                        @Override
                        public Boolean answer(InvocationOnMock input) {
                            List<IntrusionDetectionEvent> receivedEvents =
                                    (List<IntrusionDetectionEvent>) input.getArguments()[0];
                            for (IntrusionDetectionEvent event : receivedEvents) {
                                if (event.getType() == IntrusionDetectionEvent.SECURITY_EVENT) {
                                    SecurityEvent securityEvent = event.getSecurityEvent();
                                    Object[] eventData = (Object[]) securityEvent.getData();
                                    if (securityEvent.getTag() == SecurityLog.TAG_KEY_GENERATED
                                            && eventData[1].equals(eventString)) {
                                        latch.countDown();
                                    }
                                }
                            }
                            return true;
                        }
                    })
            .when(mIntrusionDetectionEventTransportConnection).addData(any());
        mDataAggregator.enable();

        // Generate the security event.
        generateSecurityEvent(eventString);
        TestApis.devicePolicy().forceSecurityLogs();

        // Verify the event is received.
        mTestLooper.startAutoDispatch();
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        mTestLooper.stopAutoDispatch();

        mDataAggregator.disable();
    }

    @Test
    @RequireRunOnSystemUser
    @EnsureHasPermission(CommonPermissions.MANAGE_DEVICE_POLICY_AUDIT_LOGGING)
    public void testDataAggregator_AddNetworkEvent() throws Exception {
        mIntrusionDetectionService.setState(STATE_ENABLED);
        ServiceThread mockThread = spy(ServiceThread.class);
        mDataAggregator.setHandler(mLooperOfDataAggregator, mockThread);
        assertTrue(mDataAggregator.initialize());

        // Network logging may log multiple and callbacks, so create a latch to wait for
        // the given event.
        // eventServer must be a valid domain to generate a network log event.
        String eventServer = "google.com";
        final CountDownLatch latch = new CountDownLatch(1);
        // TODO: Replace this mock when the IntrusionDetectionEventTransportConnection is ready.
        doAnswer(
                    new Answer<Boolean>() {
                        @Override
                        public Boolean answer(InvocationOnMock input) {
                            List<IntrusionDetectionEvent> receivedEvents =
                                    (List<IntrusionDetectionEvent>) input.getArguments()[0];
                            for (IntrusionDetectionEvent event : receivedEvents) {
                                if (event.getType()
                                        == IntrusionDetectionEvent.NETWORK_EVENT_DNS) {
                                    DnsEvent dnsEvent = event.getDnsEvent();
                                    if (dnsEvent.getHostname().equals(eventServer)) {
                                        latch.countDown();
                                    }
                                }
                            }
                            return true;
                        }
                    })
            .when(mIntrusionDetectionEventTransportConnection).addData(any());
        mDataAggregator.enable();

        // Generate the network event.
        generateNetworkEvent(eventServer);
        TestApis.devicePolicy().forceNetworkLogs();

        // Verify the event is received.
        mTestLooper.startAutoDispatch();
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        mTestLooper.stopAutoDispatch();

        mDataAggregator.disable();
    }

    /** Emits a given string into security log (if enabled). */
    private void generateSecurityEvent(String eventString)
            throws IllegalArgumentException, GeneralSecurityException, IOException {
        if (eventString == null || eventString.isEmpty()) {
            throw new IllegalArgumentException(
                    "Error generating security event: eventString must not be empty");
        }

        final KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        keyGen.initialize(
                new KeyGenParameterSpec.Builder(eventString, KeyProperties.PURPOSE_SIGN).build());
        // Emit key generation event.
        final KeyPair keyPair = keyGen.generateKeyPair();
        assertNotNull(keyPair);

        final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        // Emit key destruction event.
        ks.deleteEntry(eventString);
    }

    /** Emits a given string into network log (if enabled). */
    private void generateNetworkEvent(String server) throws IllegalArgumentException, IOException {
        if (server == null || server.isEmpty()) {
            throw new IllegalArgumentException(
                    "Error generating network event: server must not be empty");
        }

        HttpURLConnection urlConnection = null;
        int connectionTimeoutMS = 2_000;
        try (PermissionContext p = TestApis.permissions().withPermission(INTERNET)) {
            final URL url = new URL("http://" + server);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(connectionTimeoutMS);
            urlConnection.setReadTimeout(connectionTimeoutMS);
            urlConnection.getResponseCode();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private class MockInjector implements IntrusionDetectionService.Injector {
        private final Context mContext;

        MockInjector(Context context) {
            mContext = context;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public PermissionEnforcer getPermissionEnforcer() {
            return mPermissionEnforcer;
        }

        @Override
        public Looper getLooper() {
            return mLooper;
        }

        @Override
        public IntrusionDetectionEventTransportConnection
                getIntrusionDetectionEventransportConnection() {
            mIntrusionDetectionEventTransportConnection =
                    spy(new IntrusionDetectionEventTransportConnection(mContext));
            return mIntrusionDetectionEventTransportConnection;
        }

        @Override
        public DataAggregator getDataAggregator(
                IntrusionDetectionService intrusionDetectionService) {
            mDataAggregator = spy(new DataAggregator(mContext, intrusionDetectionService));
            return mDataAggregator;
        }
    }

    private static class StateCallback extends IIntrusionDetectionServiceStateCallback.Stub {
        int mState = STATE_UNKNOWN;

        @Override
        public void onStateChange(int state) throws RemoteException {
            mState = state;
        }
    }

    private static class CommandCallback extends IIntrusionDetectionServiceCommandCallback.Stub {
        Integer mErrorCode = null;

        public void reset() {
            mErrorCode = null;
        }

        @Override
        public void onSuccess() throws RemoteException {

        }

        @Override
        public void onFailure(int errorCode) throws RemoteException {
            mErrorCode = errorCode;
        }
    }
}
