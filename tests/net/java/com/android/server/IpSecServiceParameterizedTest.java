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
import static android.system.OsConstants.AF_INET6;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.net.INetd;
import android.net.IpSecAlgorithm;
import android.net.IpSecConfig;
import android.net.IpSecManager;
import android.net.IpSecSpiResponse;
import android.net.IpSecTransform;
import android.net.IpSecTransformResponse;
import android.net.IpSecTunnelInterfaceResponse;
import android.net.IpSecUdpEncapResponse;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.support.test.filters.SmallTest;
import android.system.Os;
import android.test.mock.MockContext;

import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Unit tests for {@link IpSecService}. */
@SmallTest
@RunWith(Parameterized.class)
public class IpSecServiceParameterizedTest {

    private static final int TEST_SPI = 0xD1201D;

    private final String mSourceAddr;
    private final String mDestinationAddr;
    private final LinkAddress mLocalInnerAddress;
    private final int mFamily;

    private static final int[] ADDRESS_FAMILIES =
            new int[] {AF_INET, AF_INET6};

    @Parameterized.Parameters
    public static Collection ipSecConfigs() {
        return Arrays.asList(
                new Object[][] {
                {"1.2.3.4", "8.8.4.4", "10.0.1.1/24", AF_INET},
                {"2601::2", "2601::10", "2001:db8::1/64", AF_INET6}
        });
    }

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

    AppOpsManager mMockAppOps = mock(AppOpsManager.class);

    MockContext mMockContext = new MockContext() {
        @Override
        public Object getSystemService(String name) {
            switch(name) {
                case Context.APP_OPS_SERVICE:
                    return mMockAppOps;
                default:
                    return null;
            }
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            if (permission == android.Manifest.permission.MANAGE_IPSEC_TUNNELS) {
                return;
            }
            throw new SecurityException("Unavailable permission requested");
        }
    };

    INetd mMockNetd;
    IpSecService.IpSecServiceConfiguration mMockIpSecSrvConfig;
    IpSecService mIpSecService;
    Network fakeNetwork = new Network(0xAB);
    int mUid = Os.getuid();

    private static final IpSecAlgorithm AUTH_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4);
    private static final IpSecAlgorithm CRYPT_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY);
    private static final IpSecAlgorithm AEAD_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, AEAD_KEY, 128);
    private static final int REMOTE_ENCAP_PORT = 4500;

    public IpSecServiceParameterizedTest(
            String sourceAddr, String destAddr, String localInnerAddr, int family) {
        mSourceAddr = sourceAddr;
        mDestinationAddr = destAddr;
        mLocalInnerAddress = new LinkAddress(localInnerAddr);
        mFamily = family;
    }

    @Before
    public void setUp() throws Exception {
        mMockNetd = mock(INetd.class);
        mMockIpSecSrvConfig = mock(IpSecService.IpSecServiceConfiguration.class);
        mIpSecService = new IpSecService(mMockContext, mMockIpSecSrvConfig);

        // Injecting mock netd
        when(mMockIpSecSrvConfig.getNetdInstance()).thenReturn(mMockNetd);
        // A package granted the AppOp for MANAGE_IPSEC_TUNNELS will be MODE_ALLOWED.
        when(mMockAppOps.noteOp(anyInt(), anyInt(), eq("blessedPackage")))
            .thenReturn(AppOpsManager.MODE_ALLOWED);
        // A system package will not be granted the app op, so this should fall back to
        // a permissions check, which should pass.
        when(mMockAppOps.noteOp(anyInt(), anyInt(), eq("systemPackage")))
            .thenReturn(AppOpsManager.MODE_DEFAULT);
        // A mismatch between the package name and the UID will return MODE_IGNORED.
        when(mMockAppOps.noteOp(anyInt(), anyInt(), eq("badPackage")))
            .thenReturn(AppOpsManager.MODE_IGNORED);
    }

    //TODO: Add a test to verify SPI.

    @Test
    public void testIpSecServiceReserveSpi() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), eq(mDestinationAddr), eq(TEST_SPI)))
                .thenReturn(TEST_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.allocateSecurityParameterIndex(
                        mDestinationAddr, TEST_SPI, new Binder());
        assertEquals(IpSecManager.Status.OK, spiResp.status);
        assertEquals(TEST_SPI, spiResp.spi);
    }

    @Test
    public void testReleaseSecurityParameterIndex() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), eq(mDestinationAddr), eq(TEST_SPI)))
                .thenReturn(TEST_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.allocateSecurityParameterIndex(
                        mDestinationAddr, TEST_SPI, new Binder());

        mIpSecService.releaseSecurityParameterIndex(spiResp.resourceId);

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(0, userRecord.mSpiQuotaTracker.mCurrent);
        try {
            userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testSecurityParameterIndexBinderDeath() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), eq(mDestinationAddr), eq(TEST_SPI)))
                .thenReturn(TEST_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.allocateSecurityParameterIndex(
                        mDestinationAddr, TEST_SPI, new Binder());

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);

        refcountedRecord.binderDied();

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mSpiQuotaTracker.mCurrent);
        try {
            userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    private int getNewSpiResourceId(String remoteAddress, int returnSpi) throws Exception {
        when(mMockNetd.ipSecAllocateSpi(anyInt(), anyString(), anyString(), anyInt()))
                .thenReturn(returnSpi);

        IpSecSpiResponse spi =
                mIpSecService.allocateSecurityParameterIndex(
                        NetworkUtils.numericToInetAddress(remoteAddress).getHostAddress(),
                        IpSecManager.INVALID_SECURITY_PARAMETER_INDEX,
                        new Binder());
        return spi.resourceId;
    }

    private void addDefaultSpisAndRemoteAddrToIpSecConfig(IpSecConfig config) throws Exception {
        config.setSpiResourceId(getNewSpiResourceId(mDestinationAddr, TEST_SPI));
        config.setSourceAddress(mSourceAddr);
        config.setDestinationAddress(mDestinationAddr);
    }

    private void addAuthAndCryptToIpSecConfig(IpSecConfig config) throws Exception {
        config.setEncryption(CRYPT_ALGO);
        config.setAuthentication(AUTH_ALGO);
    }

    private void addEncapSocketToIpSecConfig(int resourceId, IpSecConfig config) throws Exception {
        config.setEncapType(IpSecTransform.ENCAP_ESPINUDP);
        config.setEncapSocketResourceId(resourceId);
        config.setEncapRemotePort(REMOTE_ENCAP_PORT);
    }

    private void verifyTransformNetdCalledForCreatingSA(
            IpSecConfig config, IpSecTransformResponse resp) throws Exception {
        verifyTransformNetdCalledForCreatingSA(config, resp, 0);
    }

    private void verifyTransformNetdCalledForCreatingSA(
            IpSecConfig config, IpSecTransformResponse resp, int encapSocketPort) throws Exception {
        IpSecAlgorithm auth = config.getAuthentication();
        IpSecAlgorithm crypt = config.getEncryption();
        IpSecAlgorithm authCrypt = config.getAuthenticatedEncryption();

        verify(mMockNetd, times(1))
                .ipSecAddSecurityAssociation(
                        eq(mUid),
                        eq(config.getMode()),
                        eq(config.getSourceAddress()),
                        eq(config.getDestinationAddress()),
                        eq((config.getNetwork() != null) ? config.getNetwork().netId : 0),
                        eq(TEST_SPI),
                        eq(0),
                        eq(0),
                        eq((auth != null) ? auth.getName() : ""),
                        eq((auth != null) ? auth.getKey() : new byte[] {}),
                        eq((auth != null) ? auth.getTruncationLengthBits() : 0),
                        eq((crypt != null) ? crypt.getName() : ""),
                        eq((crypt != null) ? crypt.getKey() : new byte[] {}),
                        eq((crypt != null) ? crypt.getTruncationLengthBits() : 0),
                        eq((authCrypt != null) ? authCrypt.getName() : ""),
                        eq((authCrypt != null) ? authCrypt.getKey() : new byte[] {}),
                        eq((authCrypt != null) ? authCrypt.getTruncationLengthBits() : 0),
                        eq(config.getEncapType()),
                        eq(encapSocketPort),
                        eq(config.getEncapRemotePort()),
                        eq(config.getXfrmInterfaceId()));
    }

    @Test
    public void testCreateTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);
    }

    @Test
    public void testCreateTransformAead() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);

        ipSecConfig.setAuthenticatedEncryption(AEAD_ALGO);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);
    }

    @Test
    public void testCreateTransportModeTransformWithEncap() throws Exception {
        IpSecUdpEncapResponse udpSock = mIpSecService.openUdpEncapsulationSocket(0, new Binder());

        IpSecConfig ipSecConfig = new IpSecConfig();
        ipSecConfig.setMode(IpSecTransform.MODE_TRANSPORT);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);
        addEncapSocketToIpSecConfig(udpSock.resourceId, ipSecConfig);

        if (mFamily == AF_INET) {
            IpSecTransformResponse createTransformResp =
                    mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
            assertEquals(IpSecManager.Status.OK, createTransformResp.status);

            verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp, udpSock.port);
        } else {
            try {
                IpSecTransformResponse createTransformResp =
                        mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
                fail("Expected IllegalArgumentException on attempt to use UDP Encap in IPv6");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testCreateTunnelModeTransformWithEncap() throws Exception {
        IpSecUdpEncapResponse udpSock = mIpSecService.openUdpEncapsulationSocket(0, new Binder());

        IpSecConfig ipSecConfig = new IpSecConfig();
        ipSecConfig.setMode(IpSecTransform.MODE_TUNNEL);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);
        addEncapSocketToIpSecConfig(udpSock.resourceId, ipSecConfig);

        if (mFamily == AF_INET) {
            IpSecTransformResponse createTransformResp =
                    mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
            assertEquals(IpSecManager.Status.OK, createTransformResp.status);

            verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp, udpSock.port);
        } else {
            try {
                IpSecTransformResponse createTransformResp =
                        mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
                fail("Expected IllegalArgumentException on attempt to use UDP Encap in IPv6");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void testCreateTwoTransformsWithSameSpis() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        // Attempting to create transform a second time with the same SPIs should throw an error...
        try {
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
                fail("IpSecService should have thrown an error for reuse of SPI");
        } catch (IllegalStateException expected) {
        }

        // ... even if the transform is deleted
        mIpSecService.deleteTransform(createTransformResp.resourceId);
        try {
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
                fail("IpSecService should have thrown an error for reuse of SPI");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testReleaseOwnedSpi() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(1, userRecord.mSpiQuotaTracker.mCurrent);
        mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());
        verify(mMockNetd, times(0))
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());
        // quota is not released until the SPI is released by the Transform
        assertEquals(1, userRecord.mSpiQuotaTracker.mCurrent);
    }

    @Test
    public void testDeleteTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
        mIpSecService.deleteTransform(createTransformResp.resourceId);

        verify(mMockNetd, times(1))
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        assertEquals(0, userRecord.mTransformQuotaTracker.mCurrent);
        assertEquals(1, userRecord.mSpiQuotaTracker.mCurrent);

        mIpSecService.releaseSecurityParameterIndex(ipSecConfig.getSpiResourceId());
        // Verify that ipSecDeleteSa was not called when the SPI was released because the
        // ownedByTransform property should prevent it; (note, the called count is cumulative).
        verify(mMockNetd, times(1))
                .ipSecDeleteSecurityAssociation(
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
        assertEquals(0, userRecord.mSpiQuotaTracker.mCurrent);

        try {
            userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                    createTransformResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testTransportModeTransformBinderDeath() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                        createTransformResp.resourceId);

        refcountedRecord.binderDied();

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(mUid),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mTransformQuotaTracker.mCurrent);
        try {
            userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                    createTransformResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {

        }
    }

    @Test
    public void testApplyTransportModeTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");

        Socket socket = new Socket();
        socket.bind(null);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);

        int resourceId = createTransformResp.resourceId;
        mIpSecService.applyTransportModeTransform(pfd, IpSecManager.DIRECTION_OUT, resourceId);

        verify(mMockNetd)
                .ipSecApplyTransportModeTransform(
                        eq(pfd),
                        eq(mUid),
                        eq(IpSecManager.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI));
    }

    @Test
    public void testRemoveTransportModeTransform() throws Exception {
        Socket socket = new Socket();
        socket.bind(null);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(socket);
        mIpSecService.removeTransportModeTransforms(pfd);

        verify(mMockNetd).ipSecRemoveTransportModeTransform(pfd);
    }

    private IpSecTunnelInterfaceResponse createAndValidateTunnel(
            String localAddr, String remoteAddr, String pkgName) {
        IpSecTunnelInterfaceResponse createTunnelResp =
                mIpSecService.createTunnelInterface(
                        mSourceAddr, mDestinationAddr, fakeNetwork, new Binder(), pkgName);

        assertNotNull(createTunnelResp);
        assertEquals(IpSecManager.Status.OK, createTunnelResp.status);
        return createTunnelResp;
    }

    @Test
    public void testCreateTunnelInterface() throws Exception {
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, "blessedPackage");

        // Check that we have stored the tracking object, and retrieve it
        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                        createTunnelResp.resourceId);

        assertEquals(1, userRecord.mTunnelQuotaTracker.mCurrent);
        verify(mMockNetd)
                .ipSecAddTunnelInterface(
                        eq(createTunnelResp.interfaceName),
                        eq(mSourceAddr),
                        eq(mDestinationAddr),
                        anyInt(),
                        anyInt(),
                        anyInt());
    }

    @Test
    public void testDeleteTunnelInterface() throws Exception {
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, "blessedPackage");

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);

        mIpSecService.deleteTunnelInterface(createTunnelResp.resourceId, "blessedPackage");

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mTunnelQuotaTracker.mCurrent);
        verify(mMockNetd).ipSecRemoveTunnelInterface(eq(createTunnelResp.interfaceName));
        try {
            userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                    createTunnelResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testTunnelInterfaceBinderDeath() throws Exception {
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, "blessedPackage");

        IpSecService.UserRecord userRecord = mIpSecService.mUserResourceTracker.getUserRecord(mUid);
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                        createTunnelResp.resourceId);

        refcountedRecord.binderDied();

        // Verify quota and RefcountedResource objects cleaned up
        assertEquals(0, userRecord.mTunnelQuotaTracker.mCurrent);
        verify(mMockNetd).ipSecRemoveTunnelInterface(eq(createTunnelResp.interfaceName));
        try {
            userRecord.mTunnelInterfaceRecords.getRefcountedResourceOrThrow(
                    createTunnelResp.resourceId);
            fail("Expected IllegalArgumentException on attempt to access deleted resource");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testApplyTunnelModeTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        ipSecConfig.setMode(IpSecTransform.MODE_TUNNEL);
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder(), "blessedPackage");
        IpSecTunnelInterfaceResponse createTunnelResp =
                createAndValidateTunnel(mSourceAddr, mDestinationAddr, "blessedPackage");

        int transformResourceId = createTransformResp.resourceId;
        int tunnelResourceId = createTunnelResp.resourceId;
        mIpSecService.applyTunnelModeTransform(tunnelResourceId, IpSecManager.DIRECTION_OUT,
                transformResourceId, "blessedPackage");

        for (int selAddrFamily : ADDRESS_FAMILIES) {
            verify(mMockNetd)
                    .ipSecUpdateSecurityPolicy(
                            eq(mUid),
                            eq(selAddrFamily),
                            eq(IpSecManager.DIRECTION_OUT),
                            anyString(),
                            anyString(),
                            eq(TEST_SPI),
                            anyInt(), // iKey/oKey
                            anyInt(), // mask
                            eq(tunnelResourceId));
        }

        ipSecConfig.setXfrmInterfaceId(tunnelResourceId);
        verifyTransformNetdCalledForCreatingSA(ipSecConfig, createTransformResp);
    }

    @Test
    public void testAddRemoveAddressFromTunnelInterface() throws Exception {
        for (String pkgName : new String[]{"blessedPackage", "systemPackage"}) {
            IpSecTunnelInterfaceResponse createTunnelResp =
                    createAndValidateTunnel(mSourceAddr, mDestinationAddr, pkgName);
            mIpSecService.addAddressToTunnelInterface(
                    createTunnelResp.resourceId, mLocalInnerAddress, pkgName);
            verify(mMockNetd, times(1))
                    .interfaceAddAddress(
                            eq(createTunnelResp.interfaceName),
                            eq(mLocalInnerAddress.getAddress().getHostAddress()),
                            eq(mLocalInnerAddress.getPrefixLength()));
            mIpSecService.removeAddressFromTunnelInterface(
                    createTunnelResp.resourceId, mLocalInnerAddress, pkgName);
            verify(mMockNetd, times(1))
                    .interfaceDelAddress(
                            eq(createTunnelResp.interfaceName),
                            eq(mLocalInnerAddress.getAddress().getHostAddress()),
                            eq(mLocalInnerAddress.getPrefixLength()));
            mIpSecService.deleteTunnelInterface(createTunnelResp.resourceId, pkgName);
        }
    }

    @Ignore
    @Test
    public void testAddTunnelFailsForBadPackageName() throws Exception {
        try {
            IpSecTunnelInterfaceResponse createTunnelResp =
                    createAndValidateTunnel(mSourceAddr, mDestinationAddr, "badPackage");
            fail("Expected a SecurityException for badPackage.");
        } catch (SecurityException expected) {
        }
    }
}
