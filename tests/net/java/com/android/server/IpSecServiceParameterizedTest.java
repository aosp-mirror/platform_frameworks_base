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
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
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
import android.net.NetworkUtils;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.support.test.filters.SmallTest;
import android.system.OsConstants;

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

    private static final int DROID_SPI = 0xD1201D;
    private static final int DROID_SPI2 = DROID_SPI + 1;

    private final String mRemoteAddr;

    @Parameterized.Parameters
    public static Collection ipSecConfigs() {
        return Arrays.asList(new Object[][] {{"8.8.4.4"}, {"2601::10"}});
    }

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

    public IpSecServiceParameterizedTest(String remoteAddr) {
        mRemoteAddr = remoteAddr;
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
        when(mMockNetd.ipSecAllocateSpi(
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        eq(mRemoteAddr),
                        eq(DROID_SPI)))
                .thenReturn(DROID_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.reserveSecurityParameterIndex(
                        IpSecTransform.DIRECTION_OUT, mRemoteAddr, DROID_SPI, new Binder());
        assertEquals(IpSecManager.Status.OK, spiResp.status);
        assertEquals(DROID_SPI, spiResp.spi);
    }

    @Test
    public void testReleaseSecurityParameterIndex() throws Exception {
        when(mMockNetd.ipSecAllocateSpi(
                        anyInt(),
                        eq(IpSecTransform.DIRECTION_OUT),
                        anyString(),
                        eq(mRemoteAddr),
                        eq(DROID_SPI)))
                .thenReturn(DROID_SPI);

        IpSecSpiResponse spiResp =
                mIpSecService.reserveSecurityParameterIndex(
                        IpSecTransform.DIRECTION_OUT, mRemoteAddr, DROID_SPI, new Binder());

        mIpSecService.releaseSecurityParameterIndex(spiResp.resourceId);

        verify(mMockNetd)
                .ipSecDeleteSecurityAssociation(
                        eq(spiResp.resourceId), anyInt(), anyString(), anyString(), eq(DROID_SPI));
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

        /** Allocate and add SPI records in the IpSecService through IpSecManager interface. */
        IpSecManager.SecurityParameterIndex outSpi =
                ipSecManager.reserveSecurityParameterIndex(
                        IpSecTransform.DIRECTION_OUT,
                        NetworkUtils.numericToInetAddress(mRemoteAddr));
        IpSecManager.SecurityParameterIndex inSpi =
                ipSecManager.reserveSecurityParameterIndex(
                        IpSecTransform.DIRECTION_IN,
                        NetworkUtils.numericToInetAddress(mRemoteAddr));

        IpSecConfig config = new IpSecConfig();
        config.setSpiResourceId(IpSecTransform.DIRECTION_IN, inSpi.getResourceId());
        config.setSpiResourceId(IpSecTransform.DIRECTION_OUT, outSpi.getResourceId());
        config.setEncryption(IpSecTransform.DIRECTION_OUT, encryptAlgo);
        config.setAuthentication(IpSecTransform.DIRECTION_OUT, authAlgo);
        config.setEncryption(IpSecTransform.DIRECTION_IN, encryptAlgo);
        config.setAuthentication(IpSecTransform.DIRECTION_IN, authAlgo);
        config.setRemoteAddress(mRemoteAddr);
        return config;
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
