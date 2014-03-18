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

import android.content.Context;
import android.net.LinkAddress;
import android.net.LocalSocket;
import android.net.LocalServerSocket;
import android.os.Binder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import com.android.server.net.BaseNetworkObserver;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Tests for {@link NetworkManagementService}.
 */
@LargeTest
public class NetworkManagementServiceTest extends AndroidTestCase {

    private static final String SOCKET_NAME = "__test__NetworkManagementServiceTest";
    private NetworkManagementService mNMService;
    private LocalServerSocket mServerSocket;
    private LocalSocket mSocket;
    private OutputStream mOutputStream;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        // TODO: make this unnecessary. runtest might already make it unnecessary.
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().toString());

        // Set up a sheltered test environment.
        BroadcastInterceptingContext context = new BroadcastInterceptingContext(getContext());
        mServerSocket = new LocalServerSocket(SOCKET_NAME);

        // Start the service and wait until it connects to our socket.
        mNMService = NetworkManagementService.create(context, SOCKET_NAME);
        mSocket = mServerSocket.accept();
        mOutputStream = mSocket.getOutputStream();
    }

    @Override
    public void tearDown() throws Exception {
        if (mSocket != null) mSocket.close();
        if (mServerSocket != null) mServerSocket.close();
        super.tearDown();
    }

    /**
     * Sends a message on the netd socket and gives the events some time to make it back.
     */
    private void sendMessage(String message) throws IOException {
        // Strings are null-terminated, so add "\0" at the end.
        mOutputStream.write((message + "\0").getBytes());
    }

    private static <T> T expectSoon(T mock) {
        return verify(mock, timeout(100));
    }

    /**
     * Tests that network observers work properly.
     */
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

        sendMessage("613 IfaceClass active rmnet0");
        expectSoon(observer).interfaceClassDataActivityChanged("rmnet0", true, 0);

        sendMessage("613 IfaceClass active rmnet0 1234");
        expectSoon(observer).interfaceClassDataActivityChanged("rmnet0", true, 1234);

        sendMessage("613 IfaceClass idle eth0");
        expectSoon(observer).interfaceClassDataActivityChanged("eth0", false, 0);

        sendMessage("613 IfaceClass idle eth0 1234");
        expectSoon(observer).interfaceClassDataActivityChanged("eth0", false, 1234);

        sendMessage("613 IfaceClass reallyactive rmnet0 1234");
        expectSoon(observer).interfaceClassDataActivityChanged("rmnet0", false, 1234);

        sendMessage("613 InterfaceClass reallyactive rmnet0");
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
