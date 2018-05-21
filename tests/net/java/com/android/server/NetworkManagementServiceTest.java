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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Context;
import android.net.INetd;
import android.net.LinkAddress;
import android.net.LocalSocket;
import android.net.LocalServerSocket;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.IBinder;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.server.NetworkManagementService.SystemServices;
import com.android.server.net.BaseNetworkObserver;

import java.io.IOException;
import java.io.OutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link NetworkManagementService}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkManagementServiceTest {

    private static final String SOCKET_NAME = "__test__NetworkManagementServiceTest";
    private NetworkManagementService mNMService;
    private LocalServerSocket mServerSocket;
    private LocalSocket mSocket;
    private OutputStream mOutputStream;

    @Mock private Context mContext;
    @Mock private IBatteryStats.Stub mBatteryStatsService;
    @Mock private INetd.Stub mNetdService;

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

        // Set up a sheltered test environment.
        mServerSocket = new LocalServerSocket(SOCKET_NAME);

        // Start the service and wait until it connects to our socket.
        mNMService = NetworkManagementService.create(mContext, SOCKET_NAME, mServices);
        mSocket = mServerSocket.accept();
        mOutputStream = mSocket.getOutputStream();
    }

    @After
    public void tearDown() throws Exception {
        mNMService.shutdown();
        // Once NetworkManagementService#shutdown() actually does something and shutdowns
        // the underlying NativeDaemonConnector, the block below should be uncommented.
        // if (mOutputStream != null) mOutputStream.close();
        // if (mSocket != null) mSocket.close();
        // if (mServerSocket != null) mServerSocket.close();
    }

    /**
     * Sends a message on the netd socket and gives the events some time to make it back.
     */
    private void sendMessage(String message) throws IOException {
        // Strings are null-terminated, so add "\0" at the end.
        mOutputStream.write((message + "\0").getBytes());
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
        reset(observer);

        // Now send NetworkManagementService messages and ensure that the observer methods are
        // called. After every valid message we expect a callback soon after; to ensure that
        // invalid messages don't cause any callbacks, we call verifyNoMoreInteractions at the end.

        /**
         * Interface changes.
         */
        sendMessage("600 Iface added rmnet12");
        expectSoon(observer).interfaceAdded("rmnet12");

        sendMessage("600 Iface removed eth1");
        expectSoon(observer).interfaceRemoved("eth1");

        sendMessage("607 Iface removed eth1");
        // Invalid code.

        sendMessage("600 Iface borked lo down");
        // Invalid event.

        sendMessage("600 Iface changed clat4 up again");
        // Extra tokens.

        sendMessage("600 Iface changed clat4 up");
        expectSoon(observer).interfaceStatusChanged("clat4", true);

        sendMessage("600 Iface linkstate rmnet0 down");
        expectSoon(observer).interfaceLinkStateChanged("rmnet0", false);

        sendMessage("600 IFACE linkstate clat4 up");
        // Invalid group.

        /**
         * Bandwidth control events.
         */
        sendMessage("601 limit alert data rmnet_usb0");
        expectSoon(observer).limitReached("data", "rmnet_usb0");

        sendMessage("601 invalid alert data rmnet0");
        // Invalid group.

        sendMessage("601 limit increased data rmnet0");
        // Invalid event.


        /**
         * Interface class activity.
         */

        sendMessage("613 IfaceClass active 1 1234 10012");
        expectSoon(observer).interfaceClassDataActivityChanged("1", true, 1234);

        sendMessage("613 IfaceClass idle 9 5678");
        expectSoon(observer).interfaceClassDataActivityChanged("9", false, 5678);

        sendMessage("613 IfaceClass reallyactive 9 4321");
        expectSoon(observer).interfaceClassDataActivityChanged("9", false, 4321);

        sendMessage("613 InterfaceClass reallyactive 1");
        // Invalid group.


        /**
         * IP address changes.
         */
        sendMessage("614 Address updated fe80::1/64 wlan0 128 253");
        expectSoon(observer).addressUpdated("wlan0", new LinkAddress("fe80::1/64", 128, 253));

        // There is no "added", so we take this as "removed".
        sendMessage("614 Address added fe80::1/64 wlan0 128 253");
        expectSoon(observer).addressRemoved("wlan0", new LinkAddress("fe80::1/64", 128, 253));

        sendMessage("614 Address removed 2001:db8::1/64 wlan0 1 0");
        expectSoon(observer).addressRemoved("wlan0", new LinkAddress("2001:db8::1/64", 1, 0));

        sendMessage("614 Address removed 2001:db8::1/64 wlan0 1");
        // Not enough arguments.

        sendMessage("666 Address removed 2001:db8::1/64 wlan0 1 0");
        // Invalid code.


        /**
         * DNS information broadcasts.
         */
        sendMessage("615 DnsInfo servers rmnet_usb0 3600 2001:db8::1");
        expectSoon(observer).interfaceDnsServerInfo("rmnet_usb0", 3600,
                new String[]{"2001:db8::1"});

        sendMessage("615 DnsInfo servers wlan0 14400 2001:db8::1,2001:db8::2");
        expectSoon(observer).interfaceDnsServerInfo("wlan0", 14400,
                new String[]{"2001:db8::1", "2001:db8::2"});

        // We don't check for negative lifetimes, only for parse errors.
        sendMessage("615 DnsInfo servers wlan0 -3600 ::1");
        expectSoon(observer).interfaceDnsServerInfo("wlan0", -3600,
                new String[]{"::1"});

        sendMessage("615 DnsInfo servers wlan0 SIXHUNDRED ::1");
        // Non-numeric lifetime.

        sendMessage("615 DnsInfo servers wlan0 2001:db8::1");
        // Missing lifetime.

        sendMessage("615 DnsInfo servers wlan0 3600");
        // No servers.

        sendMessage("615 DnsInfo servers 3600 wlan0 2001:db8::1,2001:db8::2");
        // Non-numeric lifetime.

        sendMessage("615 DnsInfo wlan0 7200 2001:db8::1,2001:db8::2");
        // Invalid tokens.

        sendMessage("666 DnsInfo servers wlan0 5400 2001:db8::1");
        // Invalid code.

        // No syntax checking on the addresses.
        sendMessage("615 DnsInfo servers wlan0 600 ,::,,foo,::1,");
        expectSoon(observer).interfaceDnsServerInfo("wlan0", 600,
                new String[]{"", "::", "", "foo", "::1"});

        // Make sure nothing else was called.
        verifyNoMoreInteractions(observer);
    }
}
