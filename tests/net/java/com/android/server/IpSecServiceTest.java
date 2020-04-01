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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
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
import android.net.IpSecUdpEncapResponse;
import android.os.Binder;
import android.os.INetworkManagementService;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import dalvik.system.SocketTagger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Unit tests for {@link IpSecService}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class IpSecServiceTest {

    private static final int DROID_SPI = 0xD1201D;
    private static final int MAX_NUM_ENCAP_SOCKETS = 100;
    private static final int MAX_NUM_SPIS = 100;
    private static final int TEST_UDP_ENCAP_INVALID_PORT = 100;
    private static final int TEST_UDP_ENCAP_PORT_OUT_RANGE = 100000;

    private static final InetAddress INADDR_ANY;

    private static final byte[] AEAD_KEY = {
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
        0x73, 0x61, 0x6C, 0x74
    };
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

    private static final IpSecAlgorithm AUTH_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4);
    private static final IpSecAlgorithm CRYPT_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY);
    private static final IpSecAlgorithm AEAD_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, AEAD_KEY, 128);

    static {
        try {
            INADDR_ANY = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    Context mMockContext;
    INetworkManagementService mMockNetworkManager;
    INetd mMockNetd;
    IpSecService.IpSecServiceConfiguration mMockIpSecSrvConfig;
    IpSecService mIpSecService;

    @Before
    public void setUp() throws Exception {
        mMockContext = mock(Context.class);
        mMockNetworkManager = mock(INetworkManagementService.class);
        mMockNetd = mock(INetd.class);
        mMockIpSecSrvConfig = mock(IpSecService.IpSecServiceConfiguration.class);
        mIpSecService = new IpSecService(mMockContext, mMockNetworkManager, mMockIpSecSrvConfig);

        // Injecting mock netd
        when(mMockIpSecSrvConfig.getNetdInstance()).thenReturn(mMockNetd);
    }

    @Test
    public void testIpSecServiceCreate() throws InterruptedException {
        IpSecService ipSecSrv = IpSecService.create(mMockContext, mMockNetworkManager);
        assertNotNull(ipSecSrv);
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
        int localport = -1;
        IpSecUdpEncapResponse udpEncapResp = null;

        for (int i = 0; i < IpSecService.MAX_PORT_BIND_ATTEMPTS; i++) {
            localport = findUnusedPort();

            udpEncapResp = mIpSecService.openUdpEncapsulationSocket(localport, new Binder());
            assertNotNull(udpEncapResp);
            if (udpEncapResp.status == IpSecManager.Status.OK) {
                break;
            }

            // Else retry to reduce possibility for port-bind failures.
        }

        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        assertEquals(localport, udpEncapResp.port);

        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(Os.getuid());
        assertEquals(0, userRecord.mSocketQuotaTracker.mCurrent);
        try {
            userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(udpEncapResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testUdpEncapsulationSocketBinderDeath() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());

        IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(Os.getuid());
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(
                        udpEncapResp.resourceId);

        refcountedRecord.binderDied();

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mSocketQuotaTracker.mCurrent);
        try {
            userRecord.mEncapSocketRecords.getRefcountedResourceOrThrow(udpEncapResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testOpenUdpEncapsulationSocketAfterClose() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        int localport = udpEncapResp.port;

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
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        int localport = udpEncapResp.port;

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
        assertNotEquals(0, udpEncapResp.port);
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
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);
        int localport = udpEncapResp.port;

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

    @Test
    public void testValidateAlgorithmsAuth() {
        // Validate that correct algorithm type succeeds
        IpSecConfig config = new IpSecConfig();
        config.setAuthentication(AUTH_ALGO);
        mIpSecService.validateAlgorithms(config);

        // Validate that incorrect algorithm types fails
        for (IpSecAlgorithm algo : new IpSecAlgorithm[] {CRYPT_ALGO, AEAD_ALGO}) {
            try {
                config = new IpSecConfig();
                config.setAuthentication(algo);
                mIpSecService.validateAlgorithms(config);
                fail("Did not throw exception on invalid algorithm type");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testValidateAlgorithmsCrypt() {
        // Validate that correct algorithm type succeeds
        IpSecConfig config = new IpSecConfig();
        config.setEncryption(CRYPT_ALGO);
        mIpSecService.validateAlgorithms(config);

        // Validate that incorrect algorithm types fails
        for (IpSecAlgorithm algo : new IpSecAlgorithm[] {AUTH_ALGO, AEAD_ALGO}) {
            try {
                config = new IpSecConfig();
                config.setEncryption(algo);
                mIpSecService.validateAlgorithms(config);
                fail("Did not throw exception on invalid algorithm type");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testValidateAlgorithmsAead() {
        // Validate that correct algorithm type succeeds
        IpSecConfig config = new IpSecConfig();
        config.setAuthenticatedEncryption(AEAD_ALGO);
        mIpSecService.validateAlgorithms(config);

        // Validate that incorrect algorithm types fails
        for (IpSecAlgorithm algo : new IpSecAlgorithm[] {AUTH_ALGO, CRYPT_ALGO}) {
            try {
                config = new IpSecConfig();
                config.setAuthenticatedEncryption(algo);
                mIpSecService.validateAlgorithms(config);
                fail("Did not throw exception on invalid algorithm type");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testValidateAlgorithmsAuthCrypt() {
        // Validate that correct algorithm type succeeds
        IpSecConfig config = new IpSecConfig();
        config.setAuthentication(AUTH_ALGO);
        config.setEncryption(CRYPT_ALGO);
        mIpSecService.validateAlgorithms(config);
    }

    @Test
    public void testValidateAlgorithmsNoAlgorithms() {
        IpSecConfig config = new IpSecConfig();
        try {
            mIpSecService.validateAlgorithms(config);
            fail("Expected exception; no algorithms specified");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testValidateAlgorithmsAeadWithAuth() {
        IpSecConfig config = new IpSecConfig();
        config.setAuthenticatedEncryption(AEAD_ALGO);
        config.setAuthentication(AUTH_ALGO);
        try {
            mIpSecService.validateAlgorithms(config);
            fail("Expected exception; both AEAD and auth algorithm specified");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testValidateAlgorithmsAeadWithCrypt() {
        IpSecConfig config = new IpSecConfig();
        config.setAuthenticatedEncryption(AEAD_ALGO);
        config.setEncryption(CRYPT_ALGO);
        try {
            mIpSecService.validateAlgorithms(config);
            fail("Expected exception; both AEAD and crypt algorithm specified");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testValidateAlgorithmsAeadWithAuthAndCrypt() {
        IpSecConfig config = new IpSecConfig();
        config.setAuthenticatedEncryption(AEAD_ALGO);
        config.setAuthentication(AUTH_ALGO);
        config.setEncryption(CRYPT_ALGO);
        try {
            mIpSecService.validateAlgorithms(config);
            fail("Expected exception; AEAD, auth and crypt algorithm specified");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testDeleteInvalidTransform() throws Exception {
        try {
            mIpSecService.deleteTransform(1);
            fail("IllegalArgumentException not thrown");
        } catch (IllegalArgumentException e) {
        }
    }

    @Test
    public void testRemoveTransportModeTransform() throws Exception {
        Socket socket = new Socket();
        socket.bind(null);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);
        mIpSecService.removeTransportModeTransforms(pfd);

        verify(mMockNetd).ipSecRemoveTransportModeTransform(pfd);
    }

    @Test
    public void testValidateIpAddresses() throws Exception {
        String[] invalidAddresses =
                new String[] {"www.google.com", "::", "2001::/64", "0.0.0.0", ""};
        for (String address : invalidAddresses) {
            try {
                IpSecSpiResponse spiResp =
                        mIpSecService.allocateSecurityParameterIndex(
                                address, DROID_SPI, new Binder());
                fail("Invalid address was passed through IpSecService validation: " + address);
            } catch (IllegalArgumentException e) {
            } catch (Exception e) {
                fail(
                        "Invalid InetAddress was not caught in validation: "
                                + address
                                + ", Exception: "
                                + e);
            }
        }
    }

    /**
     * This function checks if the number of encap UDP socket that one UID can reserve has a
     * reasonable limit.
     */
    @Test
    public void testSocketResourceTrackerLimitation() throws Exception {
        List<IpSecUdpEncapResponse> openUdpEncapSockets = new ArrayList<IpSecUdpEncapResponse>();
        // Reserve sockets until it fails.
        for (int i = 0; i < MAX_NUM_ENCAP_SOCKETS; i++) {
            IpSecUdpEncapResponse newUdpEncapSocket =
                    mIpSecService.openUdpEncapsulationSocket(0, new Binder());
            assertNotNull(newUdpEncapSocket);
            if (IpSecManager.Status.OK != newUdpEncapSocket.status) {
                break;
            }
            openUdpEncapSockets.add(newUdpEncapSocket);
        }
        // Assert that the total sockets quota has a reasonable limit.
        assertTrue("No UDP encap socket was open", !openUdpEncapSockets.isEmpty());
        assertTrue(
                "Number of open UDP encap sockets is out of bound",
                openUdpEncapSockets.size() < MAX_NUM_ENCAP_SOCKETS);

        // Try to reserve one more UDP encapsulation socket, and should fail.
        IpSecUdpEncapResponse extraUdpEncapSocket =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(extraUdpEncapSocket);
        assertEquals(IpSecManager.Status.RESOURCE_UNAVAILABLE, extraUdpEncapSocket.status);

        // Close one of the open UDP encapsulation sockets.
        mIpSecService.closeUdpEncapsulationSocket(openUdpEncapSockets.get(0).resourceId);
        openUdpEncapSockets.get(0).fileDescriptor.close();
        openUdpEncapSockets.remove(0);

        // Try to reserve one more UDP encapsulation socket, and should be successful.
        extraUdpEncapSocket = mIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(extraUdpEncapSocket);
        assertEquals(IpSecManager.Status.OK, extraUdpEncapSocket.status);
        openUdpEncapSockets.add(extraUdpEncapSocket);

        // Close open UDP sockets.
        for (IpSecUdpEncapResponse openSocket : openUdpEncapSockets) {
            mIpSecService.closeUdpEncapsulationSocket(openSocket.resourceId);
            openSocket.fileDescriptor.close();
        }
    }

    /**
     * This function checks if the number of SPI that one UID can reserve has a reasonable limit.
     * This test does not test for both address families or duplicate SPIs because resource tracking
     * code does not depend on them.
     */
    @Test
    public void testSpiResourceTrackerLimitation() throws Exception {
        List<IpSecSpiResponse> reservedSpis = new ArrayList<IpSecSpiResponse>();
        // Return the same SPI for all SPI allocation since IpSecService only
        // tracks the resource ID.
        when(mMockNetd.ipSecAllocateSpi(
                        anyInt(),
                        anyString(),
                        eq(InetAddress.getLoopbackAddress().getHostAddress()),
                        anyInt()))
                .thenReturn(DROID_SPI);
        // Reserve spis until it fails.
        for (int i = 0; i < MAX_NUM_SPIS; i++) {
            IpSecSpiResponse newSpi =
                    mIpSecService.allocateSecurityParameterIndex(
                            InetAddress.getLoopbackAddress().getHostAddress(),
                            DROID_SPI + i,
                            new Binder());
            assertNotNull(newSpi);
            if (IpSecManager.Status.OK != newSpi.status) {
                break;
            }
            reservedSpis.add(newSpi);
        }
        // Assert that the SPI quota has a reasonable limit.
        assertTrue(reservedSpis.size() > 0 && reservedSpis.size() < MAX_NUM_SPIS);

        // Try to reserve one more SPI, and should fail.
        IpSecSpiResponse extraSpi =
                mIpSecService.allocateSecurityParameterIndex(
                        InetAddress.getLoopbackAddress().getHostAddress(),
                        DROID_SPI + MAX_NUM_SPIS,
                        new Binder());
        assertNotNull(extraSpi);
        assertEquals(IpSecManager.Status.RESOURCE_UNAVAILABLE, extraSpi.status);

        // Release one reserved spi.
        mIpSecService.releaseSecurityParameterIndex(reservedSpis.get(0).resourceId);
        reservedSpis.remove(0);

        // Should successfully reserve one more spi.
        extraSpi =
                mIpSecService.allocateSecurityParameterIndex(
                        InetAddress.getLoopbackAddress().getHostAddress(),
                        DROID_SPI + MAX_NUM_SPIS,
                        new Binder());
        assertNotNull(extraSpi);
        assertEquals(IpSecManager.Status.OK, extraSpi.status);

        // Release reserved SPIs.
        for (IpSecSpiResponse spiResp : reservedSpis) {
            mIpSecService.releaseSecurityParameterIndex(spiResp.resourceId);
        }
    }

    @Test
    public void testUidFdtagger() throws Exception {
        SocketTagger actualSocketTagger = SocketTagger.get();

        try {
            FileDescriptor sockFd = Os.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);

            // Has to be done after socket creation because BlockGuardOS calls tag on new sockets
            SocketTagger mockSocketTagger = mock(SocketTagger.class);
            SocketTagger.set(mockSocketTagger);

            mIpSecService.mUidFdTagger.tag(sockFd, Process.LAST_APPLICATION_UID);
            verify(mockSocketTagger).tag(eq(sockFd));
        } finally {
            SocketTagger.set(actualSocketTagger);
        }
    }

    /**
     * Checks if two file descriptors point to the same file.
     *
     * <p>According to stat.h documentation, the correct way to check for equivalent or duplicated
     * file descriptors is to check their inode and device. These two entries uniquely identify any
     * file.
     */
    private boolean fileDescriptorsEqual(FileDescriptor fd1, FileDescriptor fd2) {
        try {
            StructStat fd1Stat = Os.fstat(fd1);
            StructStat fd2Stat = Os.fstat(fd2);

            return fd1Stat.st_ino == fd2Stat.st_ino && fd1Stat.st_dev == fd2Stat.st_dev;
        } catch (ErrnoException e) {
            return false;
        }
    }

    @Test
    public void testOpenUdpEncapSocketTagsSocket() throws Exception {
        IpSecService.UidFdTagger mockTagger = mock(IpSecService.UidFdTagger.class);
        IpSecService testIpSecService = new IpSecService(
                mMockContext, mMockNetworkManager, mMockIpSecSrvConfig, mockTagger);

        IpSecUdpEncapResponse udpEncapResp =
                testIpSecService.openUdpEncapsulationSocket(0, new Binder());
        assertNotNull(udpEncapResp);
        assertEquals(IpSecManager.Status.OK, udpEncapResp.status);

        FileDescriptor sockFd = udpEncapResp.fileDescriptor.getFileDescriptor();
        ArgumentMatcher<FileDescriptor> fdMatcher =
                (argFd) -> {
                    return fileDescriptorsEqual(sockFd, argFd);
                };
        verify(mockTagger).tag(argThat(fdMatcher), eq(Os.getuid()));

        testIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
        udpEncapResp.fileDescriptor.close();
    }

    @Test
    public void testOpenUdpEncapsulationSocketCallsSetEncapSocketOwner() throws Exception {
        IpSecUdpEncapResponse udpEncapResp =
                mIpSecService.openUdpEncapsulationSocket(0, new Binder());

        FileDescriptor sockFd = udpEncapResp.fileDescriptor.getFileDescriptor();
        ArgumentMatcher<ParcelFileDescriptor> fdMatcher = (arg) -> {
                    try {
                        StructStat sockStat = Os.fstat(sockFd);
                        StructStat argStat = Os.fstat(arg.getFileDescriptor());

                        return sockStat.st_ino == argStat.st_ino
                                && sockStat.st_dev == argStat.st_dev;
                    } catch (ErrnoException e) {
                        return false;
                    }
                };

        verify(mMockNetd).ipSecSetEncapSocketOwner(argThat(fdMatcher), eq(Os.getuid()));
        mIpSecService.closeUdpEncapsulationSocket(udpEncapResp.resourceId);
    }

    @Test
    public void testReserveNetId() {
        int start = mIpSecService.TUN_INTF_NETID_START;
        for (int i = 0; i < mIpSecService.TUN_INTF_NETID_RANGE; i++) {
            assertEquals(start + i, mIpSecService.reserveNetId());
        }

        // Check that resource exhaustion triggers an exception
        try {
            mIpSecService.reserveNetId();
            fail("Did not throw error for all netIds reserved");
        } catch (IllegalStateException expected) {
        }

        // Now release one and try again
        int releasedNetId =
                mIpSecService.TUN_INTF_NETID_START + mIpSecService.TUN_INTF_NETID_RANGE / 2;
        mIpSecService.releaseNetId(releasedNetId);
        assertEquals(releasedNetId, mIpSecService.reserveNetId());
    }
}
