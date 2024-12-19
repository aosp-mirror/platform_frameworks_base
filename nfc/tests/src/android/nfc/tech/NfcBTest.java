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

import static android.nfc.tech.NfcB.EXTRA_APPDATA;
import static android.nfc.tech.NfcB.EXTRA_PROTINFO;

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

public class NfcBTest {
    private final byte[] mSampleAppDate = new byte[] {1, 2, 3};
    private final byte[] mSampleProtInfo = new byte[] {3, 2, 1};
    @Mock
    private Tag mMockTag;
    @Mock
    private Bundle mMockBundle;
    @Mock
    private INfcTag mMockTagService;
    private NfcB mNfcB;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockBundle.getByteArray(EXTRA_APPDATA)).thenReturn(mSampleAppDate);
        when(mMockBundle.getByteArray(EXTRA_PROTINFO)).thenReturn(mSampleProtInfo);
        when(mMockTag.getTechExtras(TagTechnology.NFC_B)).thenReturn(mMockBundle);

        mNfcB = new NfcB(mMockTag);
    }

    @Test
    public void testGetApplicationData() {
        assertNotNull(mNfcB.getApplicationData());
    }

    @Test
    public void testGetProtocolInfo() {
        assertNotNull(mNfcB.getProtocolInfo());
    }

    @Test
    public void testGetNfcBInstance() {
        Tag tag = mock(Tag.class);
        when(tag.hasTech(TagTechnology.NFC_B)).thenReturn(true);
        when(tag.getTechExtras(TagTechnology.NFC_B)).thenReturn(mMockBundle);

        assertNotNull(NfcB.get(tag));
        verify(tag).hasTech(TagTechnology.NFC_B);
        verify(tag).getTechExtras(TagTechnology.NFC_B);
    }

    @Test
    public void testGetNfcBNullInstance() {
        Tag tag = mock(Tag.class);
        when(tag.hasTech(TagTechnology.NFC_B)).thenReturn(false);

        assertNull(NfcB.get(tag));
        verify(tag).hasTech(TagTechnology.NFC_B);
        verify(tag, never()).getTechExtras(TagTechnology.NFC_B);
    }


    @Test
    public void testTransceive() throws IOException, RemoteException {
        byte[] sampleData = new byte[] {1, 2, 3, 4, 5};
        TransceiveResult mockTransceiveResult = mock(TransceiveResult.class);
        when(mMockTag.getConnectedTechnology()).thenReturn(TagTechnology.NFC_B);
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTag.getServiceHandle()).thenReturn(1);
        when(mMockTagService.transceive(1, sampleData, true))
                .thenReturn(mockTransceiveResult);
        when(mockTransceiveResult.getResponseOrThrow()).thenReturn(sampleData);

        mNfcB.transceive(sampleData);
        verify(mMockTag).getTagService();
        verify(mMockTag).getServiceHandle();
    }

    @Test
    public void testGetMaxTransceiveLength() throws RemoteException {
        when(mMockTag.getTagService()).thenReturn(mMockTagService);
        when(mMockTagService.getMaxTransceiveLength(TagTechnology.NFC_B)).thenReturn(1);

        mNfcB.getMaxTransceiveLength();
        verify(mMockTag).getTagService();
    }
}
