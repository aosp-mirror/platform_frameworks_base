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

package android.nfc.technology;

import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;

import java.io.IOException;

/**
 * A low-level connection to a {@link Tag} using the ISO-DEP technology, also known as
 * ISO1443-4.
 *
 * <p>You can acquire this kind of connection with {@link NfcAdapter#getTechnology}.
 * Use this class to send and receive data with {@link #transceive transceive()}.
 *
 * <p>Applications must implement their own protocol stack on top of
 * {@link #transceive transceive()}.
 *
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 */
public final class IsoDep extends BasicTagTechnology {
    /** @hide */
    public static final String EXTRA_HI_LAYER_RESP = "hiresp";
    /** @hide */
    public static final String EXTRA_HIST_BYTES = "histbytes";

    private byte[] mHiLayerResponse = null;
    private byte[] mHistBytes = null;

    /** @hide */
    public IsoDep(NfcAdapter adapter, Tag tag, Bundle extras)
            throws RemoteException {
        super(adapter, tag, TagTechnology.ISO_DEP);
        if (extras != null) {
            mHiLayerResponse = extras.getByteArray(EXTRA_HI_LAYER_RESP);
            mHistBytes = extras.getByteArray(EXTRA_HIST_BYTES);
        }
    }

    /**
     * Return the historical bytes if the tag is using {@link NfcA}, null otherwise.
     */
    public byte[] getHistoricalBytes() {
        return mHistBytes;
    }

    /**
     * Return the hi layer response bytes if the tag is using {@link NfcB}, null otherwise.
     */
    public byte[] getHiLayerResponse() {
        return mHiLayerResponse;
    }

    /**
     * Send data to a tag and receive the response.
     * <p>
     * This method will block until the response is received. It can be canceled
     * with {@link #close}.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     *
     * @param data bytes to send
     * @return bytes received in response
     * @throws IOException if the target is lost or connection closed
     */
    public byte[] transceive(byte[] data) throws IOException {
        return transceive(data, true);
    }
}
