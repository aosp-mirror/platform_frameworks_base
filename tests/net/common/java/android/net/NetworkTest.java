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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Build;
import android.platform.test.annotations.AppModeFull;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetworkTest {
    final Network mNetwork = new Network(99);

    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

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
    @AppModeFull(reason = "Socket cannot bind in instant app mode")
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
        assertNotEquals(0, one.hashCode());
        assertNotEquals(0, two.hashCode());
        assertNotEquals(0, three.hashCode());

        // All the hashcodes are distinct.
        assertNotEquals(one.hashCode(), two.hashCode());
        assertNotEquals(one.hashCode(), three.hashCode());
        assertNotEquals(two.hashCode(), three.hashCode());

        // None of the handles are zero.
        assertNotEquals(0, one.getNetworkHandle());
        assertNotEquals(0, two.getNetworkHandle());
        assertNotEquals(0, three.getNetworkHandle());

        // All the handles are distinct.
        assertNotEquals(one.getNetworkHandle(), two.getNetworkHandle());
        assertNotEquals(one.getNetworkHandle(), three.getNetworkHandle());
        assertNotEquals(two.getNetworkHandle(), three.getNetworkHandle());

        // The handles are not equal to the hashcodes.
        assertNotEquals(one.hashCode(), one.getNetworkHandle());
        assertNotEquals(two.hashCode(), two.getNetworkHandle());
        assertNotEquals(three.hashCode(), three.getNetworkHandle());

        // Adjust as necessary to test an implementation's specific constants.
        // When running with runtest, "adb logcat -s TestRunner" can be useful.
        assertEquals(7700664333L, one.getNetworkHandle());
        assertEquals(11995631629L, two.getNetworkHandle());
        assertEquals(16290598925L, three.getNetworkHandle());
    }

    @Test
    public void testFromNetworkHandle() {
        final Network network = new Network(1234);
        assertEquals(network.getNetId(),
                Network.fromNetworkHandle(network.getNetworkHandle()).getNetId());
    }

    // Parsing private DNS bypassing handle was not supported until S
    @Test @IgnoreUpTo(Build.VERSION_CODES.R)
    public void testFromNetworkHandle_S() {
        final Network network = new Network(1234, true);

        final Network recreatedNetwork = Network.fromNetworkHandle(network.getNetworkHandle());
        assertEquals(network.netId, recreatedNetwork.netId);
        assertEquals(network.getNetIdForResolv(), recreatedNetwork.getNetIdForResolv());
    }

    @Test
    public void testGetPrivateDnsBypassingCopy() {
        final Network copy = mNetwork.getPrivateDnsBypassingCopy();
        assertEquals(mNetwork.netId, copy.netId);
        assertNotEquals(copy.netId, copy.getNetIdForResolv());
        assertNotEquals(mNetwork.getNetIdForResolv(), copy.getNetIdForResolv());
    }
}
