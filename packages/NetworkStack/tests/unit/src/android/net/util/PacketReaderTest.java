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

import static android.net.util.PacketReader.DEFAULT_RECV_BUF_SIZE;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static android.system.OsConstants.SOCK_NONBLOCK;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Handler;
import android.os.HandlerThread;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for PacketReader.
 *
 * @hide
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PacketReaderTest {
    static final InetAddress LOOPBACK6 = Inet6Address.getLoopbackAddress();
    static final StructTimeval TIMEO = StructTimeval.fromMillis(500);

    protected CountDownLatch mLatch;
    protected FileDescriptor mLocalSocket;
    protected InetSocketAddress mLocalSockName;
    protected byte[] mLastRecvBuf;
    protected boolean mStopped;
    protected HandlerThread mHandlerThread;
    protected PacketReader mReceiver;

    class UdpLoopbackReader extends PacketReader {
        public UdpLoopbackReader(Handler h) {
            super(h);
        }

        @Override
        protected FileDescriptor createFd() {
            FileDescriptor s = null;
            try {
                s = Os.socket(AF_INET6, SOCK_DGRAM | SOCK_NONBLOCK, IPPROTO_UDP);
                Os.bind(s, LOOPBACK6, 0);
                mLocalSockName = (InetSocketAddress) Os.getsockname(s);
                Os.setsockoptTimeval(s, SOL_SOCKET, SO_SNDTIMEO, TIMEO);
            } catch (ErrnoException|SocketException e) {
                closeFd(s);
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
        protected void onStart() {
            mStopped = false;
            mLatch.countDown();
        }

        @Override
        protected void onStop() {
            mStopped = true;
            mLatch.countDown();
        }
    };

    @Before
    public void setUp() {
        resetLatch();
        mLocalSocket = null;
        mLocalSockName = null;
        mLastRecvBuf = null;
        mStopped = false;

        mHandlerThread = new HandlerThread(PacketReaderTest.class.getSimpleName());
        mHandlerThread.start();
    }

    @After
    public void tearDown() throws Exception {
        if (mReceiver != null) {
            mHandlerThread.getThreadHandler().post(() -> { mReceiver.stop(); });
            waitForActivity();
        }
        mReceiver = null;
        mHandlerThread.quit();
        mHandlerThread = null;
    }

    void resetLatch() { mLatch = new CountDownLatch(1); }

    void waitForActivity() throws Exception {
        try {
            mLatch.await(1000, TimeUnit.MILLISECONDS);
        } finally {
            resetLatch();
        }
    }

    void sendPacket(byte[] contents) throws Exception {
        final DatagramSocket sender = new DatagramSocket();
        sender.connect(mLocalSockName);
        sender.send(new DatagramPacket(contents, contents.length));
        sender.close();
    }

    @Test
    public void testBasicWorking() throws Exception {
        final Handler h = mHandlerThread.getThreadHandler();
        mReceiver = new UdpLoopbackReader(h);

        h.post(() -> { mReceiver.start(); });
        waitForActivity();
        assertTrue(mLocalSockName != null);
        assertEquals(LOOPBACK6, mLocalSockName.getAddress());
        assertTrue(0 < mLocalSockName.getPort());
        assertTrue(mLocalSocket != null);
        assertFalse(mStopped);

        final byte[] one = "one 1".getBytes("UTF-8");
        sendPacket(one);
        waitForActivity();
        assertEquals(1, mReceiver.numPacketsReceived());
        assertTrue(Arrays.equals(one, mLastRecvBuf));
        assertFalse(mStopped);

        final byte[] two = "two 2".getBytes("UTF-8");
        sendPacket(two);
        waitForActivity();
        assertEquals(2, mReceiver.numPacketsReceived());
        assertTrue(Arrays.equals(two, mLastRecvBuf));
        assertFalse(mStopped);

        mReceiver.stop();
        waitForActivity();
        assertEquals(2, mReceiver.numPacketsReceived());
        assertTrue(Arrays.equals(two, mLastRecvBuf));
        assertTrue(mStopped);
        mReceiver = null;
    }

    class NullPacketReader extends PacketReader {
        public NullPacketReader(Handler h, int recvbufsize) {
            super(h, recvbufsize);
        }

        @Override
        public FileDescriptor createFd() { return null; }
    }

    @Test
    public void testMinimalRecvBufSize() throws Exception {
        final Handler h = mHandlerThread.getThreadHandler();

        for (int i : new int[]{-1, 0, 1, DEFAULT_RECV_BUF_SIZE-1}) {
            final PacketReader b = new NullPacketReader(h, i);
            assertEquals(DEFAULT_RECV_BUF_SIZE, b.recvBufSize());
        }
    }
}
