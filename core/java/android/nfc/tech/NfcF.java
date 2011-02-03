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
 * Provides access to NFC-F (JIS 6319-4) properties and I/O operations on a {@link Tag}.
 *
 * <p>Acquire a {@link NfcF} object using {@link #get}.
 * <p>The primary NFC-F I/O operation is {@link #transceive}. Applications must
 * implement their own protocol stack on top of {@link #transceive}.
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
 */
public final class NfcF extends BasicTagTechnology {
    /** @hide */
    public static final String EXTRA_SC = "systemcode";
    /** @hide */
    public static final String EXTRA_PMM = "pmm";

    private byte[] mSystemCode = null;
    private byte[] mManufacturer = null;

    /**
     * Get an instance of {@link NfcF} for the given tag.
     * <p>Returns null if {@link NfcF} was not enumerated in {@link Tag#getTechList}.
     * This indicates the tag does not support NFC-F.
     * <p>Does not cause any RF activity and does not block.
     *
     * @param tag an NFC-F compatible tag
     * @return NFC-F object
     */
    public static NfcF get(Tag tag) {
        if (!tag.hasTech(TagTechnology.NFC_F)) return null;
        try {
            return new NfcF(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public NfcF(Tag tag) throws RemoteException {
        super(tag, TagTechnology.NFC_F);
        Bundle extras = tag.getTechExtras(TagTechnology.NFC_F);
        if (extras != null) {
            mSystemCode = extras.getByteArray(EXTRA_SC);
            mManufacturer = extras.getByteArray(EXTRA_PMM);
        }
    }

    /**
     * Return the System Code bytes from tag discovery.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return System Code bytes
     */
    public byte[] getSystemCode() {
      return mSystemCode;
    }

    /**
     * Return the Manufacturer bytes from tag discovery.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return Manufacturer bytes
     */
    public byte[] getManufacturer() {
      return mManufacturer;
    }

    /**
     * Send raw NFC-F commands to the tag and receive the response.
     *
     * <p>Applications must not append the SoD (length) or EoD (CRC) to the payload,
     * it will be automatically calculated.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param data bytes to send
     * @return bytes received in response
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or this operation is canceled
     */
    public byte[] transceive(byte[] data) throws IOException {
        return transceive(data, true);
    }
}
