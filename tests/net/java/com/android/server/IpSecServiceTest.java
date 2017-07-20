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

package com.android.server;

import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.EADDRINUSE;
import static android.system.OsConstants.IPPROTO_UDP;
import static android.system.OsConstants.SOCK_DGRAM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecTransformResponse;
import android.net.IpSecUdpEncapResponse;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.support.test.filters.SmallTest;
import android.system.ErrnoException;
import android.system.Os;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link IpSecService}. */
@SmallTest
@RunWith(JUnit4.class)
public class IpSecServiceTest {

    private static final int DROID_SPI = 0xD1201D;
    private static final int DROID_SPI2 = DROID_SPI + 1;
    private static final int TEST_UDP_ENCAP_INVALID_PORT = 100;
    private static final int TEST_UDP_ENCAP_PORT_OUT_RANGE = 100000;
    private static final int TEST_UDP_ENCAP_PORT = 34567;

    private static final String IPV4_LOOPBACK = "127.0.0.1";
    private static final String IPV4_ADDR = "192.168.0.2";

    private static final InetAddress INADDR_ANY;

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private static final int[] DIRECTIONS =
            new int[] {IpSecTransform.DIRECTION_OUT, IpSecTransform.DIRECTION_IN};
    private static final byte[] CRYPT_KEY = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F
    };
    private static final byte[] AUTH_KEY = {
        0x7A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F,
        0x7A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F
    };

    Context mMockContext;
    INetd mMockNetd;
    IpSecService.IpSecServiceConfiguration mMockIpSecSrvConfig;
    IpSecService mIpSecService;

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockNetd = mock(INetd.class);
        mMockIpSecSrvConfig = mock(IpSecService.IpSecServiceConfiguration.class);
        mIpSecService = new IpSecService(mMockContext, mMockIpSecSrvConfig);

        // Injecting mock netd
        when(mMockIpSecSrvConfig.getNetdInstance()).thenReturn(mMockNetd);
    }

    @Test
    public void testIpSecServiceCreate() throws InterruptedException {
        IpSecService ipSecSrv = IpSecService.create(mMockContext);
        assertNotNull(ipSecSrv);
    }

    @Test
    public void testIpSecServiceReserveSpi() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        eq(IPV4_LOOPBACK),
                        eq(DROID_SPI)))
                .thenReturn(DROID_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.reserveSecurityParameterIndex(
                        IpSecTransform.DIRECTION_OUT, IPV4_LOOPBACK, DROID_SPI, new Binder());
        assertEquals(IpSecManager.Status.OK, spiResp.status);
        assertEquals(DROID_SPI, spiResp.spi);
    }

    @Test
    public void testReleaseSecurityParameterIndex() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        eq(IPV4_LOOPBACK),
                        eq(DROID_SPI)))
                .thenReturn(DROID_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.reserveSecurityParameterIndex(
                        IpSecTransform.DIRECTION_OUT, IPV4_LOOPBACK, DROID_SPI, new Binder());

        mIpSecService.releaseSecurityParameterIndex(spiResp.resourceId);

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(spiResp.resourceId), anyInt(), anyString(), anyString(), eq(DROID_SPI));
    }

    @Test
    public void testReleaseInvalidSecurityParameterIndex() throws Exception {
        try {
            mIpSecService.releaseSecurityParameterIndex(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    /** This function finds an available port */
    int findUnusedPort() throws Exception {
        // Get an available port.
        ServerSocket s = new ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        return port;
    }

    @Test
    public void testOpenAndCloseUdpEncapsulationSocket() throws Exception {
        int localport = findUnusedPort();

        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();

        // TODO: Added check for the resource tracker
    }

    @Test
    public void testOpenUdpEncapsulationSocketAfterClose() throws Exception {
        int localport = findUnusedPort();
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();

        /** Check if localport is available. */
        FileDescriptor newSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        Os.bind(newSocket, INADDR_ANY, localport);
        Os.close(newSocket);
    }

    /**
     * This function checks if the IpSecService holds the reserved port. If
     * closeUdpEncapsulationSocket is not called, the socket cleanup should not be complete.
     */
    @Test
    public void testUdpEncapPortNotReleased() throws Exception {
        int localport = findUnusedPort();
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        udpEncapResp.fileDescriptor.close();

        FileDescriptor newSocket = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        try {
            Os.bind(newSocket, INADDR_ANY, localport);
            fail("ErrnoException not thrown");
        } catch (ErrnoException e) {
            assertEquals(EADDRINUSE, e.errno);
        }
        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
    }

    @Test
    public void testOpenUdpEncapsulationSocketOnRandomPort() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();
    }

    @Test
    public void testOpenUdpEncapsulationSocketPortRange() throws Exception {
        try {
            mIpSecService.openUdpEncapsulationSocket(TEST_UDP_ENCAP_INVALID_PORT, new Binder());
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }

        try {
            mIpSecService.openUdpEncapsulationSocket(TEST_UDP_ENCAP_PORT_OUT_RANGE, new Binder());
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testOpenUdpEncapsulationSocketTwice() throws Exception {
        int localport = findUnusedPort();

        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);
        mIpSecService.openUdpEncapsulationSocket(localport, new Binder());

        IpSecUdpEncapResponse testUdpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
        assertEquals(IpSecManager.Status.RESOURCE_UNAVAILABLE, testUdpEncapResp.status);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();
    }

    @Test
    public void testCloseInvalidUdpEncapsulationSocket() throws Exception {
        try {
            mIpSecService.closeUdpEncapsulationSocket(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    IpSecConfig buildIpSecConfig() throws Exception {
        IpSecManager ipSecManager = new IpSecManager(mIpSecService);

        // Mocking the netd to allocate SPI
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyInt(), anyString(), anyString(), anyInt()))
                .thenReturn(DROID_SPI)
                .thenReturn(DROID_SPI2);

        IpSecAlgorithm encryptAlgo = new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY);
        IpSecAlgorithm authAlgo =
                new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 8);

        InetAddress localAddr = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});

        /** Allocate and add SPI records in the IpSecService through IpSecManager interface. */
        IpSecManager.SecurityParameterIndex outSpi =
                ipSecManager.reserveSecurityParameterIndex(IpSecTransform.DIRECTION_OUT, localAddr);
        IpSecManager.SecurityParameterIndex inSpi =
                ipSecManager.reserveSecurityParameterIndex(IpSecTransform.DIRECTION_IN, localAddr);

        IpSecConfig ipSecConfig =
                new IpSecTransform.Builder(mMockContext)
                        .setSpi(IpSecTransform.DIRECTION_OUT, outSpi)
                        .setSpi(IpSecTransform.DIRECTION_IN, inSpi)
                        .setEncryption(IpSecTransform.DIRECTION_OUT, encryptAlgo)
                        .setAuthentication(IpSecTransform.DIRECTION_OUT, authAlgo)
                        .setEncryption(IpSecTransform.DIRECTION_IN, encryptAlgo)
                        .setAuthentication(IpSecTransform.DIRECTION_IN, authAlgo)
                        .getIpSecConfig();
        return ipSecConfig;
    }

    @Test
    public void testCreateTransportModeTransform() throws Exception {
        IpSecConfig ipSecConfig = buildIpSecConfig();

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransportModeTransform(ipSecConfig, new Binder());
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verify(mMockNetd)
                .ipSecAddSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        anyLong(),
                        eq(DROID_SPI),
                        eq(IpSecAlgorithm.AUTH_HMAC_SHA256),
                        eq(AUTH_KEY),
                        anyInt(),
                        eq(IpSecAlgorithm.CRYPT_AES_CBC),
                        eq(CRYPT_KEY),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
        verify(mMockNetd)
                .ipSecAddSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_IN),
                        anyString(),
                        anyString(),
                        anyLong(),
                        eq(DROID_SPI2),
                        eq(IpSecAlgorithm.AUTH_HMAC_SHA256),
                        eq(AUTH_KEY),
                        anyInt(),
                        eq(IpSecAlgorithm.CRYPT_AES_CBC),
                        eq(CRYPT_KEY),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
    }

    @Test
    public void testDeleteTransportModeTransform() throws Exception {
        IpSecConfig ipSecConfig = buildIpSecConfig();

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransportModeTransform(ipSecConfig, new Binder());
        mIpSecService.deleteTransportModeTransform(createTransformResp.resourceId);

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        eq(DROID_SPI));
        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        eq(IpSecTransform.DIRECTION_IN),
                        anyString(),
                        anyString(),
                        eq(DROID_SPI2));
    }

    @Test
    public void testDeleteInvalidTransportModeTransform() throws Exception {
        try {
            mIpSecService.deleteTransportModeTransform(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testApplyTransportModeTransform() throws Exception {
        IpSecConfig ipSecConfig = buildIpSecConfig();

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransportModeTransform(ipSecConfig, new Binder());
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(new Socket());

        int resourceId = createTransformResp.resourceId;
        mIpSecService.applyTransportModeTransform(pfd, resourceId);

        verify(mMockNetd)
                .ipSecApplyTransportModeTransform(
                        eq(pfd.getFileDescriptor()),
                        eq(resourceId),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        eq(DROID_SPI));
        verify(mMockNetd)
                .ipSecApplyTransportModeTransform(
                        eq(pfd.getFileDescriptor()),
                        eq(resourceId),
                        eq(IpSecTransform.DIRECTION_IN),
                        anyString(),
                        anyString(),
                        eq(DROID_SPI2));
    }

    @Test
    public void testRemoveTransportModeTransform() throws Exception {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(new Socket());
        mIpSecService.removeTransportModeTransform(pfd, 1);

        verify(mMockNetd).ipSecRemoveTransportModeTransform(pfd.getFileDescriptor());
    }
}
