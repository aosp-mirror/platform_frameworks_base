/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.nfc.tech.NfcA.EXTRA_ATQA;
import static android.nfc.tech.NfcA.EXTRA_SAK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
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

public class NfcATest {
    @Mock
    private Tag mMockTag;
    @Mock
    private INfcTag mMockTagService;
    @Mock
    private Bundle mMockBundle;
    private NfcA mNfcA;
    private final byte[] mSampleArray = new byte[] {1, 2, 3};

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockBundle.getShort(EXTRA_SAK)).thenReturn((short) 1);
        when(mMockBundle.getByteArray(EXTRA_ATQA)).thenReturn(mSampleArray);
        when(mMockTag.getTechExtras(TagTechnology.NFC_A)).thenReturn(mMockBundle);

        mNfcA = new NfcA(mMockTag);
    }

    @Test
    public void testGetNfcAWithTech() {
        Tag mockTag = mock(Tag.class);
        when(mockTag.getTechExtras(TagTechnology.NFC_A)).thenReturn(mMockBundle);
        when(mockTag.hasTech(TagTechnology.NFC_A)).thenReturn(true);

        assertNotNull(NfcA.get(mockTag));
        verify(mockTag).getTechExtras(TagTechnology.NFC_A);
        verify(mockTag).hasTech(TagTechnology.NFC_A);
    }

    @Test
    public void testGetNfcAWithoutTech() {
        when(mMockTag.hasTech(TagTechnology.NFC_A)).thenReturn(false);
        assertNull(NfcA.get(mMockTag));
    }

    @Test
    public void testGetAtga() {
        assertNotNull(mNfcA.getAtqa());
    }

    @Test
    public void testGetSak() {
        assertEquals((short) 1, mNfcA.getSak());
    }

    @Test
    public void testTransceive() throws IOException, RemoteException {
        TransceiveResult mockTransceiveResult = mock(TransceiveResult.class);
        when(mMockTag.getConnectedTechnology()).thenReturn(TagTechnology.NFC_A);
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTag.getServiceHandle()).thenReturn(1);
        when(mMockTagService.transceive(1, mSampleArray, true))
                .thenReturn(mockTransceiveResult);
        when(mockTransceiveResult.getResponseOrThrow()).thenReturn(mSampleArray);

        mNfcA.transceive(mSampleArray);
        verify(mMockTag).getTagService();
        verify(mMockTag).getServiceHandle();
    }

    @Test
    public void testGetMaxTransceiveLength() throws RemoteException {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTagService.getMaxTransceiveLength(TagTechnology.NFC_A)).thenReturn(1);

        mNfcA.getMaxTransceiveLength();
        verify(mMockTag).getTagService();
    }

    @Test
    public void testSetTimeout() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.setTimeout(TagTechnology.NFC_A, 1000)).thenReturn(
                    ErrorCodes.SUCCESS);

            mNfcA.setTimeout(1000);
            verify(mMockTag).getTagService();
            verify(mMockTagService).setTimeout(TagTechnology.NFC_A, 1000);
        } catch (Exception e) {
            fail("Unexpected exception during valid setTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testSetTimeoutInvalidTimeout() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.setTimeout(TagTechnology.NFC_A, -1)).thenReturn(
                    ErrorCodes.ERROR_TIMEOUT);

            assertThrows(IllegalArgumentException.class, () -> mNfcA.setTimeout(-1));
        } catch (Exception e) {
            fail("Unexpected exception during invalid setTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testSetTimeoutRemoteException() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.setTimeout(TagTechnology.NFC_A, 1000)).thenThrow(
                    new RemoteException());

            mNfcA.setTimeout(1000); // Should not throw an exception but log it
            verify(mMockTag).getTagService();
            verify(mMockTagService).setTimeout(TagTechnology.NFC_A, 1000);
        } catch (Exception e) {
            fail("Unexpected exception during RemoteException in setTimeout: " + e.getMessage());
        }

    }

    @Test
    public void testGetTimeout() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.getTimeout(TagTechnology.NFC_A)).thenReturn(2000);

            assertEquals(2000, mNfcA.getTimeout());
            verify(mMockTag).getTagService();
            verify(mMockTagService).getTimeout(TagTechnology.NFC_A);
        } catch (Exception e) {
            fail("Unexpected exception during valid getTimeout: " + e.getMessage());
        }
    }

    @Test
    public void testGetTimeoutRemoteException() {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        try {
            when(mMockTagService.getTimeout(TagTechnology.NFC_A)).thenThrow(new RemoteException());

            assertEquals(0, mNfcA.getTimeout());
        } catch (Exception e) {
            fail("Unexpected exception during RemoteException in getTimeout: " + e.getMessage());
        }
    }
}
