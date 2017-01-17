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

package android.net.util;

import static android.system.OsConstants.*;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;

import libcore.io.IoBridge;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;


/**
 * Tests for BlockingSocketReader.
 *
 * @hide
 */
public class BlockingSocketReaderTest extends TestCase {
    static final InetAddress LOOPBACK6 = Inet6Address.getLoopbackAddress();
    static final StructTimeval TIMEO = StructTimeval.fromMillis(500);

    protected CountDownLatch mLatch;
    protected FileDescriptor mLocalSocket;
    protected InetSocketAddress mLocalSockName;
    protected byte[] mLastRecvBuf;
    protected boolean mExited;
    protected BlockingSocketReader mReceiver;

    @Override
    public void setUp() {
        resetLatch();
        mLocalSocket = null;
        mLocalSockName = null;
        mLastRecvBuf = null;
        mExited = false;

        mReceiver = new BlockingSocketReader() {
            @Override
            protected FileDescriptor createSocket() {
                FileDescriptor s = null;
                try {
                    s = Os.socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
                    Os.bind(s, LOOPBACK6, 0);
                    mLocalSockName = (InetSocketAddress) Os.getsockname(s);
                    Os.setsockoptTimeval(s, SOL_SOCKET, SO_SNDTIMEO, TIMEO);
                } catch (ErrnoException|SocketException e) {
                    closeSocket(s);
                    fail();
                    return null;
                }

                mLocalSocket = s;
                return s;
            }

            @Override
            protected void handlePacket(byte[] recvbuf, int length) {
                mLastRecvBuf = Arrays.copyOf(recvbuf, length);
                mLatch.countDown();
            }

            @Override
            protected void onExit() {
                mExited = true;
                mLatch.countDown();
            }
        };
    }

    @Override
    public void tearDown() {
        if (mReceiver != null) mReceiver.stop();
        mReceiver = null;
    }

    void resetLatch() { mLatch = new CountDownLatch(1); }

    void waitForActivity() throws Exception {
        assertTrue(mLatch.await(500, TimeUnit.MILLISECONDS));
        resetLatch();
    }

    void sendPacket(byte[] contents) throws Exception {
        final DatagramSocket sender = new DatagramSocket();
        sender.connect(mLocalSockName);
        sender.send(new DatagramPacket(contents, contents.length));
        sender.close();
    }

    public void testBasicWorking() throws Exception {
        assertTrue(mReceiver.start());
        assertTrue(mLocalSockName != null);
        assertEquals(LOOPBACK6, mLocalSockName.getAddress());
        assertTrue(0 < mLocalSockName.getPort());
        assertTrue(mLocalSocket != null);
        assertFalse(mExited);

        final byte[] one = "one 1".getBytes("UTF-8");
        sendPacket(one);
        waitForActivity();
        assertEquals(1, mReceiver.numPacketsReceived());
        assertTrue(Arrays.equals(one, mLastRecvBuf));
        assertFalse(mExited);

        final byte[] two = "two 2".getBytes("UTF-8");
        sendPacket(two);
        waitForActivity();
        assertEquals(2, mReceiver.numPacketsReceived());
        assertTrue(Arrays.equals(two, mLastRecvBuf));
        assertFalse(mExited);

        mReceiver.stop();
        waitForActivity();
        assertEquals(2, mReceiver.numPacketsReceived());
        assertTrue(Arrays.equals(two, mLastRecvBuf));
        assertTrue(mExited);
    }
}
