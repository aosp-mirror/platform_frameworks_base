/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.annotation.NonNull;
import android.content.Context;
import android.net.INetd;
import android.net.INetdUnsolicitedEventListener;
import android.net.LinkAddress;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.IBinder;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IBatteryStats;
import com.android.server.NetworkManagementService.SystemServices;
import com.android.server.net.BaseNetworkObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link NetworkManagementService}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkManagementServiceTest {

    private NetworkManagementService mNMService;

    @Mock private Context mContext;
    @Mock private IBatteryStats.Stub mBatteryStatsService;
    @Mock private INetd.Stub mNetdService;

    @NonNull
    @Captor
    private ArgumentCaptor<INetdUnsolicitedEventListener> mUnsolListenerCaptor;

    private final SystemServices mServices = new SystemServices() {
        @Override
        public IBinder getService(String name) {
            switch (name) {
                case BatteryStats.SERVICE_NAME:
                    return mBatteryStatsService;
                default:
                    throw new UnsupportedOperationException("Unknown service " + name);
            }
        }
        @Override
        public void registerLocalService(NetworkManagementInternal nmi) {
        }
        @Override
        public INetd getNetd() {
            return mNetdService;
        }
    };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doNothing().when(mNetdService)
                .registerUnsolicitedEventListener(mUnsolListenerCaptor.capture());
        // Start the service and wait until it connects to our socket.
        mNMService = NetworkManagementService.create(mContext, mServices);
    }

    @After
    public void tearDown() throws Exception {
        mNMService.shutdown();
    }

    private static <T> T expectSoon(T mock) {
        return verify(mock, timeout(200));
    }

    /**
     * Tests that network observers work properly.
     */
    @Test
    public void testNetworkObservers() throws Exception {
        BaseNetworkObserver observer = mock(BaseNetworkObserver.class);
        doReturn(new Binder()).when(observer).asBinder();  // Used by registerObserver.
        mNMService.registerObserver(observer);

        // Forget everything that happened to the mock so far, so we can explicitly verify
        // everything that happens and does not happen to it from now on.

        INetdUnsolicitedEventListener unsolListener = mUnsolListenerCaptor.getValue();
        reset(observer);
        // Now call unsolListener methods and ensure that the observer methods are
        // called. After every method we expect a callback soon after; to ensure that
        // invalid messages don't cause any callbacks, we call verifyNoMoreInteractions at the end.

        /**
         * Interface changes.
         */
        unsolListener.onInterfaceAdded("rmnet12");
        expectSoon(observer).interfaceAdded("rmnet12");

        unsolListener.onInterfaceRemoved("eth1");
        expectSoon(observer).interfaceRemoved("eth1");

        unsolListener.onInterfaceChanged("clat4", true);
        expectSoon(observer).interfaceStatusChanged("clat4", true);

        unsolListener.onInterfaceLinkStateChanged("rmnet0", false);
        expectSoon(observer).interfaceLinkStateChanged("rmnet0", false);

        /**
         * Bandwidth control events.
         */
        unsolListener.onQuotaLimitReached("data", "rmnet_usb0");
        expectSoon(observer).limitReached("data", "rmnet_usb0");

        /**
         * Interface class activity.
         */
        unsolListener.onInterfaceClassActivityChanged(true, 1, 1234, 0);
        expectSoon(observer).interfaceClassDataActivityChanged("1", true, 1234);

        unsolListener.onInterfaceClassActivityChanged(false, 9, 5678, 0);
        expectSoon(observer).interfaceClassDataActivityChanged("9", false, 5678);

        unsolListener.onInterfaceClassActivityChanged(false, 9, 4321, 0);
        expectSoon(observer).interfaceClassDataActivityChanged("9", false, 4321);

        /**
         * IP address changes.
         */
        unsolListener.onInterfaceAddressUpdated("fe80::1/64", "wlan0", 128, 253);
        expectSoon(observer).addressUpdated("wlan0", new LinkAddress("fe80::1/64", 128, 253));

        unsolListener.onInterfaceAddressRemoved("fe80::1/64", "wlan0", 128, 253);
        expectSoon(observer).addressRemoved("wlan0", new LinkAddress("fe80::1/64", 128, 253));

        unsolListener.onInterfaceAddressRemoved("2001:db8::1/64", "wlan0", 1, 0);
        expectSoon(observer).addressRemoved("wlan0", new LinkAddress("2001:db8::1/64", 1, 0));

        /**
         * DNS information broadcasts.
         */
        unsolListener.onInterfaceDnsServerInfo("rmnet_usb0", 3600, new String[]{"2001:db8::1"});
        expectSoon(observer).interfaceDnsServerInfo("rmnet_usb0", 3600,
                new String[]{"2001:db8::1"});

        unsolListener.onInterfaceDnsServerInfo("wlan0", 14400,
                new String[]{"2001:db8::1", "2001:db8::2"});
        expectSoon(observer).interfaceDnsServerInfo("wlan0", 14400,
                new String[]{"2001:db8::1", "2001:db8::2"});

        // We don't check for negative lifetimes, only for parse errors.
        unsolListener.onInterfaceDnsServerInfo("wlan0", -3600, new String[]{"::1"});
        expectSoon(observer).interfaceDnsServerInfo("wlan0", -3600,
                new String[]{"::1"});

        // No syntax checking on the addresses.
        unsolListener.onInterfaceDnsServerInfo("wlan0", 600,
                new String[]{"", "::", "", "foo", "::1"});
        expectSoon(observer).interfaceDnsServerInfo("wlan0", 600,
                new String[]{"", "::", "", "foo", "::1"});

        // Make sure nothing else was called.
        verifyNoMoreInteractions(observer);
    }
}
