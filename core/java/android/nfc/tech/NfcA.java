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

import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;

import java.io.IOException;

/**
 * A low-level connection to a {@link Tag} using the NFC-A technology, also known as
 * ISO1443-3A.
 *
 * <p>You can acquire this kind of connection with {@link #get}.
 * Use this class to send and receive data with {@link #transceive transceive()}.
 *
 * <p>Applications must implement their own protocol stack on top of
 * {@link #transceive transceive()}.
 *
 * <p class="note"><strong>Note:</strong>
 * Use of this class requires the {@link android.Manifest.permission#NFC}
 * permission.
 */
public final class NfcA extends BasicTagTechnology {
    /** @hide */
    public static final String EXTRA_SAK = "sak";
    /** @hide */
    public static final String EXTRA_ATQA = "atqa";

    private short mSak;
    private byte[] mAtqa;

    /**
     * Returns an instance of this tech for the given tag. If the tag doesn't support
     * this tech type null is returned.
     *
     * @param tag The tag to get the tech from
     */
    public static NfcA get(Tag tag) {
        if (!tag.hasTech(TagTechnology.NFC_A)) return null;
        try {
            return new NfcA(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public NfcA(Tag tag) throws RemoteException {
        super(tag, TagTechnology.NFC_A);
        Bundle extras = tag.getTechExtras(TagTechnology.NFC_A);
        mSak = extras.getShort(EXTRA_SAK);
        mAtqa = extras.getByteArray(EXTRA_ATQA);
    }

    /**
     * Returns the ATQA/SENS_RES bytes discovered at tag discovery.
     */
    public byte[] getAtqa() {
        return mAtqa;
    }

    /**
     * Returns the SAK/SEL_RES discovered at tag discovery.
     */
    public short getSak() {
        return mSak;
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
