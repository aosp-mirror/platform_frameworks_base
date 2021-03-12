/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net;

import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOTA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_SUPL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkRequest.Type.BACKGROUND_REQUEST;
import static android.net.NetworkRequest.Type.REQUEST;
import static android.net.NetworkRequest.Type.TRACK_DEFAULT;
import static android.net.NetworkRequest.Type.TRACK_SYSTEM_DEFAULT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager.NetworkCallback;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConnectivityManagerTest {

    @Mock Context mCtx;
    @Mock IConnectivityManager mService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    static NetworkCapabilities verifyNetworkCapabilities(
            int legacyType, int transportType, int... capabilities) {
        final NetworkCapabilities nc = ConnectivityManager.networkCapabilitiesForType(legacyType);
        assertNotNull(nc);
        assertTrue(nc.hasTransport(transportType));
        for (int capability : capabilities) {
            assertTrue(nc.hasCapability(capability));
        }

        return nc;
    }

    static void verifyUnrestrictedNetworkCapabilities(int legacyType, int transportType) {
        verifyNetworkCapabilities(
                legacyType,
                transportType,
                NET_CAPABILITY_INTERNET,
                NET_CAPABILITY_NOT_RESTRICTED,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);
    }

    static void verifyRestrictedMobileNetworkCapabilities(int legacyType, int capability) {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                legacyType,
                TRANSPORT_CELLULAR,
                capability,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobile() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE, TRANSPORT_CELLULAR);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileCbs() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_CBS, NET_CAPABILITY_CBS);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileDun() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_DUN, NET_CAPABILITY_DUN);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileFota() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_FOTA, NET_CAPABILITY_FOTA);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileHipri() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_HIPRI, TRANSPORT_CELLULAR);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileIms() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_IMS, NET_CAPABILITY_IMS);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileMms() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_MMS,
                TRANSPORT_CELLULAR,
                NET_CAPABILITY_MMS,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileSupl() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_SUPL,
                TRANSPORT_CELLULAR,
                NET_CAPABILITY_SUPL,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeWifi() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_WIFI, TRANSPORT_WIFI);
    }

    @Test
    public void testNetworkCapabilitiesForTypeWifiP2p() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_WIFI_P2P,
                TRANSPORT_WIFI,
                NET_CAPABILITY_NOT_RESTRICTED, NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED, NET_CAPABILITY_WIFI_P2P);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeBluetooth() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_BLUETOOTH, TRANSPORT_BLUETOOTH);
    }

    @Test
    public void testNetworkCapabilitiesForTypeEthernet() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_ETHERNET, TRANSPORT_ETHERNET);
    }

    @Test
    public void testCallbackRelease() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        NetworkRequest request = makeRequest(1);
        NetworkCallback callback = mock(ConnectivityManager.NetworkCallback.class);
        Handler handler = new Handler(Looper.getMainLooper());
        ArgumentCaptor<Messenger> captor = ArgumentCaptor.forClass(Messenger.class);

        // register callback
        when(mService.requestNetwork(anyInt(), any(), anyInt(), captor.capture(), anyInt(), any(),
                anyInt(), anyInt(), any(), nullable(String.class))).thenReturn(request);
        manager.requestNetwork(request, callback, handler);

        // callback triggers
        captor.getValue().send(makeMessage(request, ConnectivityManager.CALLBACK_AVAILABLE));
        verify(callback, timeout(500).times(1)).onAvailable(any(Network.class),
                any(NetworkCapabilities.class), any(LinkProperties.class), anyBoolean());

        // unregister callback
        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(request);

        // callback does not trigger anymore.
        captor.getValue().send(makeMessage(request, ConnectivityManager.CALLBACK_LOSING));
        verify(callback, timeout(500).times(0)).onLosing(any(), anyInt());
    }

    @Test
    public void testCallbackRecycling() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        NetworkRequest req1 = makeRequest(1);
        NetworkRequest req2 = makeRequest(2);
        NetworkCallback callback = mock(ConnectivityManager.NetworkCallback.class);
        Handler handler = new Handler(Looper.getMainLooper());
        ArgumentCaptor<Messenger> captor = ArgumentCaptor.forClass(Messenger.class);

        // register callback
        when(mService.requestNetwork(anyInt(), any(), anyInt(), captor.capture(), anyInt(), any(),
                anyInt(), anyInt(), any(), nullable(String.class))).thenReturn(req1);
        manager.requestNetwork(req1, callback, handler);

        // callback triggers
        captor.getValue().send(makeMessage(req1, ConnectivityManager.CALLBACK_AVAILABLE));
        verify(callback, timeout(100).times(1)).onAvailable(any(Network.class),
                any(NetworkCapabilities.class), any(LinkProperties.class), anyBoolean());

        // unregister callback
        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(req1);

        // callback does not trigger anymore.
        captor.getValue().send(makeMessage(req1, ConnectivityManager.CALLBACK_LOSING));
        verify(callback, timeout(100).times(0)).onLosing(any(), anyInt());

        // callback can be registered again
        when(mService.requestNetwork(anyInt(), any(), anyInt(), captor.capture(), anyInt(), any(),
                anyInt(), anyInt(), any(), nullable(String.class))).thenReturn(req2);
        manager.requestNetwork(req2, callback, handler);

        // callback triggers
        captor.getValue().send(makeMessage(req2, ConnectivityManager.CALLBACK_LOST));
        verify(callback, timeout(100).times(1)).onLost(any());

        // unregister callback
        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(req2);
    }

    // TODO: turn on this test when request  callback 1:1 mapping is enforced
    //@Test
    private void noDoubleCallbackRegistration() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        NetworkRequest request = makeRequest(1);
        NetworkCallback callback = new ConnectivityManager.NetworkCallback();
        ApplicationInfo info = new ApplicationInfo();
        // TODO: update version when starting to enforce 1:1 mapping
        info.targetSdkVersion = VERSION_CODES.N_MR1 + 1;

        when(mCtx.getApplicationInfo()).thenReturn(info);
        when(mService.requestNetwork(anyInt(), any(), anyInt(), any(), anyInt(), any(), anyInt(),
                anyInt(), any(), nullable(String.class))).thenReturn(request);

        Handler handler = new Handler(Looper.getMainLooper());
        manager.requestNetwork(request, callback, handler);

        // callback is already registered, reregistration should fail.
        Class<IllegalArgumentException> wantException = IllegalArgumentException.class;
        expectThrowable(() -> manager.requestNetwork(request, callback), wantException);

        manager.unregisterNetworkCallback(callback);
        verify(mService, times(1)).releaseNetworkRequest(request);

        // unregistering the callback should make it registrable again.
        manager.requestNetwork(request, callback);
    }

    @Test
    public void testArgumentValidation() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(mCtx, mService);

        NetworkRequest request = mock(NetworkRequest.class);
        NetworkCallback callback = mock(NetworkCallback.class);
        Handler handler = mock(Handler.class);
        NetworkCallback nullCallback = null;
        PendingIntent nullIntent = null;

        mustFail(() -> { manager.requestNetwork(null, callback); });
        mustFail(() -> { manager.requestNetwork(request, nullCallback); });
        mustFail(() -> { manager.requestNetwork(request, callback, null); });
        mustFail(() -> { manager.requestNetwork(request, callback, -1); });
        mustFail(() -> { manager.requestNetwork(request, nullIntent); });

        mustFail(() -> { manager.registerNetworkCallback(null, callback, handler); });
        mustFail(() -> { manager.registerNetworkCallback(request, null, handler); });
        mustFail(() -> { manager.registerNetworkCallback(request, callback, null); });
        mustFail(() -> { manager.registerNetworkCallback(request, nullIntent); });

        mustFail(() -> { manager.registerDefaultNetworkCallback(null, handler); });
        mustFail(() -> { manager.registerDefaultNetworkCallback(callback, null); });

        mustFail(() -> { manager.registerSystemDefaultNetworkCallback(null, handler); });
        mustFail(() -> { manager.registerSystemDefaultNetworkCallback(callback, null); });

        mustFail(() -> { manager.unregisterNetworkCallback(nullCallback); });
        mustFail(() -> { manager.unregisterNetworkCallback(nullIntent); });
        mustFail(() -> { manager.releaseNetworkRequest(nullIntent); });
    }

    static void mustFail(Runnable fn) {
        try {
            fn.run();
            fail();
        } catch (Exception expected) {
        }
    }

    @Test
    public void testRequestType() throws Exception {
        final String testPkgName = "MyPackage";
        final String testAttributionTag = "MyTag";
        final ConnectivityManager manager = new ConnectivityManager(mCtx, mService);
        when(mCtx.getOpPackageName()).thenReturn(testPkgName);
        when(mCtx.getAttributionTag()).thenReturn(testAttributionTag);
        final NetworkRequest request = makeRequest(1);
        final NetworkCallback callback = new ConnectivityManager.NetworkCallback();

        manager.requestNetwork(request, callback);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(request.networkCapabilities),
                eq(REQUEST.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag));
        reset(mService);

        // Verify that register network callback does not calls requestNetwork at all.
        manager.registerNetworkCallback(request, callback);
        verify(mService, never()).requestNetwork(anyInt(), any(), anyInt(), any(), anyInt(), any(),
                anyInt(), anyInt(), any(), any());
        verify(mService).listenForNetwork(eq(request.networkCapabilities), any(), any(), anyInt(),
                eq(testPkgName), eq(testAttributionTag));
        reset(mService);

        Handler handler = new Handler(ConnectivityThread.getInstanceLooper());

        manager.registerDefaultNetworkCallback(callback);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(null),
                eq(TRACK_DEFAULT.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag));
        reset(mService);

        manager.registerDefaultNetworkCallbackAsUid(42, callback, handler);
        verify(mService).requestNetwork(eq(42), eq(null),
                eq(TRACK_DEFAULT.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag));

        manager.requestBackgroundNetwork(request, handler, callback);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(request.networkCapabilities),
                eq(BACKGROUND_REQUEST.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag));
        reset(mService);

        manager.registerSystemDefaultNetworkCallback(callback, handler);
        verify(mService).requestNetwork(eq(Process.INVALID_UID), eq(null),
                eq(TRACK_SYSTEM_DEFAULT.ordinal()), any(), anyInt(), any(), eq(TYPE_NONE), anyInt(),
                eq(testPkgName), eq(testAttributionTag));
        reset(mService);
    }

    static Message makeMessage(NetworkRequest req, int messageType) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(NetworkRequest.class.getSimpleName(), req);
        // Pass default objects as we don't care which get passed here
        bundle.putParcelable(Network.class.getSimpleName(), new Network(1));
        bundle.putParcelable(NetworkCapabilities.class.getSimpleName(), new NetworkCapabilities());
        bundle.putParcelable(LinkProperties.class.getSimpleName(), new LinkProperties());
        Message msg = Message.obtain();
        msg.what = messageType;
        msg.setData(bundle);
        return msg;
    }

    static NetworkRequest makeRequest(int requestId) {
        NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        return new NetworkRequest(request.networkCapabilities, ConnectivityManager.TYPE_NONE,
                requestId, NetworkRequest.Type.NONE);
    }

    static void expectThrowable(Runnable block, Class<? extends Throwable> throwableType) {
        try {
            block.run();
        } catch (Throwable t) {
            if (t.getClass().equals(throwableType)) {
                return;
            }
            fail("expected exception of type " + throwableType + ", but was " + t.getClass());
        }
        fail("expected exception of type " + throwableType);
    }
}
