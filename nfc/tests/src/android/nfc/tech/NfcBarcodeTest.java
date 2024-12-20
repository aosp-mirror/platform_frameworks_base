/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.nfc.tech.NfcBarcode.EXTRA_BARCODE_TYPE;
import static android.nfc.tech.NfcBarcode.TYPE_KOVIO;
import static android.nfc.tech.NfcBarcode.TYPE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class NfcBarcodeTest {
    @Mock
    private Tag mMockTag;
    @Mock
    private Bundle mMockBundle;
    private NfcBarcode mNfcBarcode;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockBundle.getInt(EXTRA_BARCODE_TYPE)).thenReturn(TYPE_KOVIO);
        when(mMockTag.getTechExtras(TagTechnology.NFC_BARCODE)).thenReturn(mMockBundle);

        mNfcBarcode = new NfcBarcode(mMockTag);
    }

    @Test
    public void testGetNfcBarcodeInstance() {
        Tag mockTag = mock(Tag.class);
        when(mockTag.hasTech(TagTechnology.NFC_BARCODE)).thenReturn(true);
        when(mockTag.getTechExtras(TagTechnology.NFC_BARCODE)).thenReturn(mMockBundle);

        assertNotNull(NfcBarcode.get(mockTag));
        verify(mockTag).hasTech(TagTechnology.NFC_BARCODE);
        verify(mockTag).getTechExtras(TagTechnology.NFC_BARCODE);
    }

    @Test(expected = NullPointerException.class)
    public void testGetNfcBarcodeInstanceWithException() {
        Tag mockTag = mock(Tag.class);
        when(mockTag.hasTech(TagTechnology.NFC_BARCODE)).thenReturn(true);
        when(mockTag.getTechExtras(TagTechnology.NFC_BARCODE)).thenReturn(null);

        assertNull(NfcBarcode.get(mockTag));
        verify(mockTag).hasTech(TagTechnology.NFC_BARCODE);
        verify(mockTag).getTechExtras(TagTechnology.NFC_BARCODE);
    }

    @Test
    public void testGetNfcBarcodeWithoutTech() {
        when(mMockTag.hasTech(TagTechnology.NFC_BARCODE)).thenReturn(false);

        assertNull(NfcBarcode.get(mMockTag));
    }

    @Test
    public void testGetType() {
        int result = mNfcBarcode.getType();
        assertEquals(TYPE_KOVIO, result);
    }

    @Test
    public void testGetBarcodeWithTypeKovio() {
        byte[] sampleId = "sampleId".getBytes();
        when(mMockTag.getId()).thenReturn(sampleId);

        assertEquals(sampleId, mNfcBarcode.getBarcode());
        verify(mMockTag).getId();
    }

    @Test
    public void testGetBarCodeTypeUnknown() throws RemoteException {
        when(mMockBundle.getInt(EXTRA_BARCODE_TYPE)).thenReturn(TYPE_UNKNOWN);
        when(mMockTag.getTechExtras(TagTechnology.NFC_BARCODE)).thenReturn(mMockBundle);
        mNfcBarcode = new NfcBarcode(mMockTag);

        assertNull(mNfcBarcode.getBarcode());
        verify(mMockTag, never()).getId();
    }
}
