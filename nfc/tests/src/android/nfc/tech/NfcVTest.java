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

import static android.nfc.tech.NfcV.EXTRA_DSFID;
import static android.nfc.tech.NfcV.EXTRA_RESP_FLAGS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

public class NfcVTest {
    private final byte mSampleRespFlags = (byte) 1;
    private final byte mSampleDsfId = (byte) 2;
    @Mock
    private Tag mMockTag;
    @Mock
    private INfcTag mMockTagService;
    @Mock
    private Bundle mMockBundle;
    private NfcV mNfcV;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockBundle.getByte(EXTRA_RESP_FLAGS)).thenReturn(mSampleRespFlags);
        when(mMockBundle.getByte(EXTRA_DSFID)).thenReturn(mSampleDsfId);
        when(mMockTag.getTechExtras(TagTechnology.NFC_V)).thenReturn(mMockBundle);

        mNfcV = new NfcV(mMockTag);
    }

    @Test
    public void testGetResponseFlag() {
        assertEquals(mSampleRespFlags, mNfcV.getResponseFlags());
    }

    @Test
    public void testGetDsfId() {
        assertEquals(mSampleDsfId, mNfcV.getDsfId());
    }

    @Test
    public void testGetNfcVInstance() {
        Tag tag = mock(Tag.class);
        when(tag.hasTech(TagTechnology.NFC_V)).thenReturn(true);
        when(tag.getTechExtras(TagTechnology.NFC_V)).thenReturn(mMockBundle);

        assertNotNull(NfcV.get(tag));
        verify(tag).getTechExtras(TagTechnology.NFC_V);
        verify(tag).hasTech(TagTechnology.NFC_V);
    }

    @Test
    public void testGetNfcVNullInstance() {
        Tag tag = mock(Tag.class);
        when(tag.hasTech(TagTechnology.NFC_V)).thenReturn(false);

        assertNull(NfcV.get(tag));
        verify(tag, never()).getTechExtras(TagTechnology.NFC_V);
        verify(tag).hasTech(TagTechnology.NFC_V);
    }

    @Test
    public void testTransceive() throws IOException, RemoteException {
        byte[] sampleData = new byte[] {1, 2, 3, 4, 5};
        TransceiveResult mockTransceiveResult = mock(TransceiveResult.class);
        when(mMockTag.getConnectedTechnology()).thenReturn(TagTechnology.NFC_V);
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTag.getServiceHandle()).thenReturn(1);
        when(mMockTagService.transceive(1, sampleData, true))
                .thenReturn(mockTransceiveResult);
        when(mockTransceiveResult.getResponseOrThrow()).thenReturn(sampleData);

        mNfcV.transceive(sampleData);
        verify(mMockTag).getTagService();
        verify(mMockTag).getServiceHandle();
    }

    @Test
    public void testGetMaxTransceiveLength() throws RemoteException {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTagService.getMaxTransceiveLength(TagTechnology.NFC_V)).thenReturn(1);

        mNfcV.getMaxTransceiveLength();
        verify(mMockTag).getTagService();
    }
}
