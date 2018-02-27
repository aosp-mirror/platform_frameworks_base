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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyInt;
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
import android.net.IpSecTransformResponse;
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.support.test.filters.SmallTest;
import android.system.Os;

import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Unit tests for {@link IpSecService}. */
@SmallTest
@RunWith(Parameterized.class)
public class IpSecServiceParameterizedTest {

    private static final int TEST_SPI = 0xD1201D;

    private final String mDestinationAddr;
    private final String mSourceAddr;

    @Parameterized.Parameters
    public static Collection ipSecConfigs() {
        return Arrays.asList(new Object[][] {{"1.2.3.4", "8.8.4.4"}, {"2601::2", "2601::10"}});
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

    Context mMockContext;
    INetd mMockNetd;
    IpSecService.IpSecServiceConfiguration mMockIpSecSrvConfig;
    IpSecService mIpSecService;

    private static final IpSecAlgorithm AUTH_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4);
    private static final IpSecAlgorithm CRYPT_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY);
    private static final IpSecAlgorithm AEAD_ALGO =
            new IpSecAlgorithm(IpSecAlgorithm.AUTH_CRYPT_AES_GCM, AEAD_KEY, 128);

    public IpSecServiceParameterizedTest(String sourceAddr, String destAddr) {
        mSourceAddr = sourceAddr;
        mDestinationAddr = destAddr;
    }

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
                        eq(spiResp.resourceId),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(Os.getuid());
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

        IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(Os.getuid());
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mSpiRecords.getRefcountedResourceOrThrow(spiResp.resourceId);

        refcountedRecord.binderDied();

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(spiResp.resourceId),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
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

    @Test
    public void testCreateTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder());
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verify(mMockNetd)
                .ipSecAddSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        eq(IpSecAlgorithm.AUTH_HMAC_SHA256),
                        eq(AUTH_KEY),
                        anyInt(),
                        eq(IpSecAlgorithm.CRYPT_AES_CBC),
                        eq(CRYPT_KEY),
                        anyInt(),
                        eq(""),
                        eq(new byte[] {}),
                        eq(0),
                        anyInt(),
                        anyInt(),
                        anyInt());
    }

    @Test
    public void testCreateTransformAead() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);

        ipSecConfig.setAuthenticatedEncryption(AEAD_ALGO);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder());
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        verify(mMockNetd)
                .ipSecAddSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        anyInt(),
                        anyString(),
                        anyString(),
                        anyInt(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt(),
                        eq(""),
                        eq(new byte[] {}),
                        eq(0),
                        eq(""),
                        eq(new byte[] {}),
                        eq(0),
                        eq(IpSecAlgorithm.AUTH_CRYPT_AES_GCM),
                        eq(AEAD_KEY),
                        anyInt(),
                        anyInt(),
                        anyInt(),
                        anyInt());
    }

    @Test
    public void testCreateTwoTransformsWithSameSpis() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder());
        assertEquals(IpSecManager.Status.OK, createTransformResp.status);

        // Attempting to create transform a second time with the same SPIs should throw an error...
        try {
                mIpSecService.createTransform(ipSecConfig, new Binder());
                fail("IpSecService should have thrown an error for reuse of SPI");
        } catch (IllegalStateException expected) {
        }

        // ... even if the transform is deleted
        mIpSecService.deleteTransform(createTransformResp.resourceId);
        try {
                mIpSecService.createTransform(ipSecConfig, new Binder());
                fail("IpSecService should have thrown an error for reuse of SPI");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void testDeleteTransform() throws Exception {
        IpSecConfig ipSecConfig = new IpSecConfig();
        addDefaultSpisAndRemoteAddrToIpSecConfig(ipSecConfig);
        addAuthAndCryptToIpSecConfig(ipSecConfig);

        IpSecTransformResponse createTransformResp =
                mIpSecService.createTransform(ipSecConfig, new Binder());
        mIpSecService.deleteTransform(createTransformResp.resourceId);

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
                        anyInt(),
                        anyInt());

        // Verify quota and RefcountedResource objects cleaned up
        IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(Os.getuid());
        assertEquals(0, userRecord.mTransformQuotaTracker.mCurrent);
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
                mIpSecService.createTransform(ipSecConfig, new Binder());

        IpSecService.UserRecord userRecord =
                mIpSecService.mUserResourceTracker.getUserRecord(Os.getuid());
        IpSecService.RefcountedResource refcountedRecord =
                userRecord.mTransformRecords.getRefcountedResourceOrThrow(
                        createTransformResp.resourceId);

        refcountedRecord.binderDied();

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(createTransformResp.resourceId),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI),
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
                mIpSecService.createTransform(ipSecConfig, new Binder());
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(new Socket());

        int resourceId = createTransformResp.resourceId;
        mIpSecService.applyTransportModeTransform(pfd, IpSecManager.DIRECTION_OUT, resourceId);

        verify(mMockNetd)
                .ipSecApplyTransportModeTransform(
                        eq(pfd.getFileDescriptor()),
                        eq(resourceId),
                        eq(IpSecManager.DIRECTION_OUT),
                        anyString(),
                        anyString(),
                        eq(TEST_SPI));
    }

    @Test
    public void testRemoveTransportModeTransform() throws Exception {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.fromSocket(new Socket());
        mIpSecService.removeTransportModeTransforms(pfd);

        verify(mMockNetd).ipSecRemoveTransportModeTransform(pfd.getFileDescriptor());
    }
}
