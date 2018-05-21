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

package android.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Network;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.SocketException;
import java.util.Objects;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkTest {
    final Network mNetwork = new Network(99);

    @Test
    public void testBindSocketOfInvalidFdThrows() throws Exception {

        final FileDescriptor fd = new FileDescriptor();
        assertFalse(fd.valid());

        try {
            mNetwork.bindSocket(fd);
            fail("SocketException not thrown");
        } catch (SocketException expected) {}
    }

    @Test
    public void testBindSocketOfNonSocketFdThrows() throws Exception {
        final File devNull = new File("/dev/null");
        assertTrue(devNull.canRead());

        final FileInputStream fis = new FileInputStream(devNull);
        assertTrue(null != fis.getFD());
        assertTrue(fis.getFD().valid());

        try {
            mNetwork.bindSocket(fis.getFD());
            fail("SocketException not thrown");
        } catch (SocketException expected) {}
    }

    @Test
    public void testBindSocketOfConnectedDatagramSocketThrows() throws Exception {
        final DatagramSocket mDgramSocket = new DatagramSocket(0, (InetAddress) Inet6Address.ANY);
        mDgramSocket.connect((InetAddress) Inet6Address.LOOPBACK, 53);
        assertTrue(mDgramSocket.isConnected());

        try {
            mNetwork.bindSocket(mDgramSocket);
            fail("SocketException not thrown");
        } catch (SocketException expected) {}
    }

    @Test
    public void testBindSocketOfLocalSocketThrows() throws Exception {
        final LocalSocket mLocalClient = new LocalSocket();
        mLocalClient.bind(new LocalSocketAddress("testClient"));
        assertTrue(mLocalClient.getFileDescriptor().valid());

        try {
            mNetwork.bindSocket(mLocalClient.getFileDescriptor());
            fail("SocketException not thrown");
        } catch (SocketException expected) {}

        final LocalServerSocket mLocalServer = new LocalServerSocket("testServer");
        mLocalClient.connect(mLocalServer.getLocalSocketAddress());
        assertTrue(mLocalClient.isConnected());

        try {
            mNetwork.bindSocket(mLocalClient.getFileDescriptor());
            fail("SocketException not thrown");
        } catch (SocketException expected) {}
    }

    @Test
    public void testZeroIsObviousForDebugging() {
        Network zero = new Network(0);
        assertEquals(0, zero.hashCode());
        assertEquals(0, zero.getNetworkHandle());
        assertEquals("0", zero.toString());
    }

    @Test
    public void testGetNetworkHandle() {
        Network one = new Network(1);
        Network two = new Network(2);
        Network three = new Network(3);

        // None of the hashcodes are zero.
        assertNotEqual(0, one.hashCode());
        assertNotEqual(0, two.hashCode());
        assertNotEqual(0, three.hashCode());

        // All the hashcodes are distinct.
        assertNotEqual(one.hashCode(), two.hashCode());
        assertNotEqual(one.hashCode(), three.hashCode());
        assertNotEqual(two.hashCode(), three.hashCode());

        // None of the handles are zero.
        assertNotEqual(0, one.getNetworkHandle());
        assertNotEqual(0, two.getNetworkHandle());
        assertNotEqual(0, three.getNetworkHandle());

        // All the handles are distinct.
        assertNotEqual(one.getNetworkHandle(), two.getNetworkHandle());
        assertNotEqual(one.getNetworkHandle(), three.getNetworkHandle());
        assertNotEqual(two.getNetworkHandle(), three.getNetworkHandle());

        // The handles are not equal to the hashcodes.
        assertNotEqual(one.hashCode(), one.getNetworkHandle());
        assertNotEqual(two.hashCode(), two.getNetworkHandle());
        assertNotEqual(three.hashCode(), three.getNetworkHandle());

        // Adjust as necessary to test an implementation's specific constants.
        // When running with runtest, "adb logcat -s TestRunner" can be useful.
        assertEquals(7700664333L, one.getNetworkHandle());
        assertEquals(11995631629L, two.getNetworkHandle());
        assertEquals(16290598925L, three.getNetworkHandle());
    }

    private static <T> void assertNotEqual(T t1, T t2) {
        assertFalse(Objects.equals(t1, t2));
    }
}
