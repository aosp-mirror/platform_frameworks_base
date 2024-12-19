/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc.tech;

import static android.nfc.tech.NfcF.EXTRA_PMM;
import static android.nfc.tech.NfcF.EXTRA_SC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.nfc.ErrorCodes;
import android.nfc.INfcTag;
import android.nfc.Tag;
import android.nfc.TransceiveResult;
import android.os.Bundle;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

public class NfcFTest {
    private final byte[] mSampleSystemCode = new byte[] {1, 2, 3};
    private final byte[] mSampleManufacturer = new byte[] {3, 2, 1};
    @Mock
    private Tag mMockTag;
    @Mock
    private INfcTag mMockTagService;
    @Mock
    private Bundle mMockBundle;
    private NfcF mNfcF;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockBundle.getByteArray(EXTRA_SC)).thenReturn(mSampleSystemCode);
        when(mMockBundle.getByteArray(EXTRA_PMM)).thenReturn(mSampleManufacturer);
        when(mMockTag.getTechExtras(TagTechnology.NFC_F)).thenReturn(mMockBundle);

        mNfcF = new NfcF(mMockTag);
    }

    @Test
    public void testGetSystemCode() {
        assertNotNull(mNfcF.getSystemCode());
    }

    @Test
    public void testGetManufacturer() {
        assertNotNull(mNfcF.getManufacturer());
    }

    @Test
    public void testGetNfcFInstanceWithTech() {
        Tag tag = mock(Tag.class);
        when(tag.getTechExtras(TagTechnology.NFC_F)).thenReturn(mMockBundle);
        when(tag.hasTech(TagTechnology.NFC_F)).thenReturn(true);

        assertNotNull(NfcF.get(tag));
        verify(tag).getTechExtras(TagTechnology.NFC_F);
        verify(tag).hasTech(TagTechnology.NFC_F);
    }

    @Test
    public void testGetNfcFInstanceWithoutTech() {
        Tag tag = mock(Tag.class);
        when(tag.hasTech(TagTechnology.NFC_F)).thenReturn(false);

        assertNull(NfcF.get(tag));
        verify(tag).hasTech(TagTechnology.NFC_F);
        verify(tag, never()).getTechExtras(TagTechnology.NFC_F);
    }

    @Test
    public void testTransceive() throws IOException, RemoteException {
        byte[] sampleData = new byte[]{1, 2, 3, 4, 5};
        TransceiveResult mockTransceiveResult = mock(TransceiveResult.class);
        when(mMockTag.getConnectedTechnology()).thenReturn(TagTechnology.NFC_F);
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTag.getServiceHandle()).thenReturn(1);
        when(mMockTagService.transceive(1, sampleData, true))
                .thenReturn(mockTransceiveResult);
        when(mockTransceiveResult.getResponseOrThrow()).thenReturn(sampleData);

        mNfcF.transceive(sampleData);
        verify(mMockTag).getTagService();
        verify(mMockTag).getServiceHandle();
    }

    @Test
    public void testGetMaxTransceiveLength() throws RemoteException {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTagService.getMaxTransceiveLength(TagTechnology.NFC_F)).thenReturn(1);

        mNfcF.getMaxTransceiveLength();
        verify(mMockTag).getTagService();
    }

    @Test
    public void testGetTimeout() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.getTimeout(TagTechnology.NFC_F)).thenReturn(2000);

            assertEquals(2000, mNfcF.getTimeout());
            verify(mMockTag).getTagService();
            verify(mMockTagService).getTimeout(TagTechnology.NFC_F);
        } catch (Exception e) {
            fail("Unexpected exception during valid getTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testGetTimeoutRemoteException() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.getTimeout(TagTechnology.NFC_F)).thenThrow(new RemoteException());

            assertEquals(0, mNfcF.getTimeout());
        } catch (Exception e) {
            fail("Unexpected exception during RemoteException in getTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testSetTimeout() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.setTimeout(TagTechnology.NFC_F, 1000)).thenReturn(
                    ErrorCodes.SUCCESS);

            mNfcF.setTimeout(1000);
            verify(mMockTag).getTagService();
            verify(mMockTagService).setTimeout(TagTechnology.NFC_F, 1000);
        } catch (Exception e) {
            fail("Unexpected exception during valid setTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testSetTimeoutInvalidTimeout() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.setTimeout(TagTechnology.NFC_F, -1)).thenReturn(
                    ErrorCodes.ERROR_TIMEOUT);

            assertThrows(IllegalArgumentException.class, () -> mNfcF.setTimeout(-1));
        } catch (Exception e) {
            fail("Unexpected exception during invalid setTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testSetTimeoutRemoteException() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.setTimeout(TagTechnology.NFC_F, 1000)).thenThrow(
                    new RemoteException());

            mNfcF.setTimeout(1000);
            verify(mMockTag).getTagService();
            verify(mMockTagService).setTimeout(TagTechnology.NFC_F, 1000);
        } catch (Exception e) {
            fail("Unexpected exception during RemoteException in setTimeout: " + e.getMessage());
        }
    }
}
