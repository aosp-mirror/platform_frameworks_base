/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.wifi.sharedconnectivity.app;

import static android.net.wifi.WifiInfo.SECURITY_TYPE_EAP;
import static android.net.wifi.WifiInfo.SECURITY_TYPE_WEP;
import static android.net.wifi.sharedconnectivity.app.DeviceInfo.DEVICE_TYPE_TABLET;
import static android.net.wifi.sharedconnectivity.app.KnownNetwork.NETWORK_SOURCE_NEARBY_SELF;
import static android.net.wifi.sharedconnectivity.app.TetherNetwork.NETWORK_TYPE_CELLULAR;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.sharedconnectivity.service.ISharedConnectivityService;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Unit tests for {@link SharedConnectivityManager}.
 */
@SmallTest
public class SharedConnectivityManagerTest {
    private static final long DEVICE_ID = 11L;
    private static final DeviceInfo DEVICE_INFO = new DeviceInfo.Builder()
            .setDeviceType(DEVICE_TYPE_TABLET).setDeviceName("TEST_NAME").setModelName("TEST_MODEL")
            .setConnectionStrength(2).setBatteryPercentage(50).build();
    private static final int NETWORK_TYPE = NETWORK_TYPE_CELLULAR;
    private static final String NETWORK_NAME = "TEST_NETWORK";
    private static final String HOTSPOT_SSID = "TEST_SSID";
    private static final int[] HOTSPOT_SECURITY_TYPES = {SECURITY_TYPE_WEP, SECURITY_TYPE_EAP};

    private static final int NETWORK_SOURCE = NETWORK_SOURCE_NEARBY_SELF;
    private static final String SSID = "TEST_SSID";
    private static final int[] SECURITY_TYPES = {SECURITY_TYPE_WEP};

    private static final int SERVICE_PACKAGE_ID = 1;
    private static final int SERVICE_CLASS_ID = 2;

    private static final String SERVICE_PACKAGE_NAME = "TEST_PACKAGE";
    private static final String SERVICE_CLASS_NAME = "TEST_CLASS";
    private static final String PACKAGE_NAME = "TEST_PACKAGE";

    @Mock Context mContext;
    @Mock
    ISharedConnectivityService mService;
    @Mock Executor mExecutor;
    @Mock
    SharedConnectivityClientCallback mClientCallback;
    @Mock Resources mResources;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        setResources(mContext);
    }

    /**
     * Verifies constructor is binding to service.
     */
    @Test
    public void testBindingToService() {
        SharedConnectivityManager.create(mContext);
        verify(mContext).bindService(any(), any(), anyInt());
    }

    /**
     * Verifies callback is registered in the service only once and only when service is not null.
     */
    @Test
    public void testRegisterCallback() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);
        assertFalse(manager.registerCallback(mExecutor, mClientCallback));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        assertTrue(manager.registerCallback(mExecutor, mClientCallback));
        verify(mService).registerCallback(any());

        // Registering the same callback twice should fail.
        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.registerCallback(mExecutor, mClientCallback);
        assertFalse(manager.registerCallback(mExecutor, mClientCallback));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).registerCallback(any());
        assertFalse(manager.registerCallback(mExecutor, mClientCallback));
    }

    /**
     * Verifies callback is unregistered from the service if it was registered before and only when
     * service is not null.
     */
    @Test
    public void testUnregisterCallback() throws Exception {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);
        assertFalse(manager.unregisterCallback(mClientCallback));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.registerCallback(mExecutor, mClientCallback);
        assertTrue(manager.unregisterCallback(mClientCallback));
        verify(mService).unregisterCallback(any());


        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.registerCallback(mExecutor, mClientCallback);
        manager.unregisterCallback(mClientCallback);
        assertFalse(manager.unregisterCallback(mClientCallback));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        doThrow(new RemoteException()).when(mService).unregisterCallback(any());
        assertFalse(manager.unregisterCallback(mClientCallback));
    }

    /**
     * Verifies service is called when not null and exceptions are handles when calling
     * connectTetherNetwork.
     */
    @Test
    public void testConnectTetherNetwork() throws RemoteException {
        TetherNetwork network = buildTetherNetwork();

        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);
        assertFalse(manager.connectTetherNetwork(network));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.connectTetherNetwork(network);
        verify(mService).connectTetherNetwork(network);

        doThrow(new RemoteException()).when(mService).connectTetherNetwork(network);
        assertFalse(manager.connectTetherNetwork(network));
    }

    /**
     * Verifies service is called when not null and exceptions are handles when calling
     * disconnectTetherNetwork.
     */
    @Test
    public void testDisconnectTetherNetwork() throws RemoteException {
        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);
        assertFalse(manager.disconnectTetherNetwork());

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.disconnectTetherNetwork();
        verify(mService).disconnectTetherNetwork();

        doThrow(new RemoteException()).when(mService).disconnectTetherNetwork();
        assertFalse(manager.disconnectTetherNetwork());
    }

    /**
     * Verifies service is called when not null and exceptions are handles when calling
     * connectKnownNetwork.
     */
    @Test
    public void testConnectKnownNetwork() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();

        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);
        assertFalse(manager.connectKnownNetwork(network));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.connectKnownNetwork(network);
        verify(mService).connectKnownNetwork(network);

        doThrow(new RemoteException()).when(mService).connectKnownNetwork(network);
        assertFalse(manager.connectKnownNetwork(network));
    }

    /**
     * Verifies service is called when not null and exceptions are handles when calling
     * forgetKnownNetwork.
     */
    @Test
    public void testForgetKnownNetwork() throws RemoteException {
        KnownNetwork network = buildKnownNetwork();

        SharedConnectivityManager manager = SharedConnectivityManager.create(mContext);
        manager.setService(null);
        assertFalse(manager.forgetKnownNetwork(network));

        manager = SharedConnectivityManager.create(mContext);
        manager.setService(mService);
        manager.forgetKnownNetwork(network);
        verify(mService).forgetKnownNetwork(network);

        doThrow(new RemoteException()).when(mService).forgetKnownNetwork(network);
        assertFalse(manager.forgetKnownNetwork(network));
    }

    private void setResources(@Mock Context context) {
        when(context.getResources()).thenReturn(mResources);
        when(context.getPackageName()).thenReturn(PACKAGE_NAME);
        when(mResources.getIdentifier(anyString(), anyString(), anyString()))
                .thenReturn(SERVICE_PACKAGE_ID, SERVICE_CLASS_ID);
        when(mResources.getString(SERVICE_PACKAGE_ID)).thenReturn(SERVICE_PACKAGE_NAME);
        when(mResources.getString(SERVICE_CLASS_ID)).thenReturn(SERVICE_CLASS_NAME);
    }

    private TetherNetwork buildTetherNetwork() {
        return new TetherNetwork.Builder()
                .setDeviceId(DEVICE_ID)
                .setDeviceInfo(DEVICE_INFO)
                .setNetworkType(NETWORK_TYPE)
                .setNetworkName(NETWORK_NAME)
                .setHotspotSsid(HOTSPOT_SSID)
                .setHotspotSecurityTypes(HOTSPOT_SECURITY_TYPES)
                .build();
    }

    private KnownNetwork buildKnownNetwork() {
        return new KnownNetwork.Builder().setNetworkSource(NETWORK_SOURCE).setSsid(SSID)
                .setSecurityTypes(SECURITY_TYPES).build();
    }
}
