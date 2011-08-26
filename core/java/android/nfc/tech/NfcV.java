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
 * Provides access to NFC-V (ISO 15693) properties and I/O operations on a {@link Tag}.
 *
 * <p>Acquire a {@link NfcV} object using {@link #get}.
 * <p>The primary NFC-V I/O operation is {@link #transceive}. Applications must
 * implement their own protocol stack on top of {@link #transceive}.
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
 */
public final class NfcV extends BasicTagTechnology {
    /** @hide */
    public static final String EXTRA_RESP_FLAGS = "respflags";

    /** @hide */
    public static final String EXTRA_DSFID = "dsfid";

    private byte mRespFlags;
    private byte mDsfId;

    /**
     * Get an instance of {@link NfcV} for the given tag.
     * <p>Returns null if {@link NfcV} was not enumerated in {@link Tag#getTechList}.
     * This indicates the tag does not support NFC-V.
     * <p>Does not cause any RF activity and does not block.
     *
     * @param tag an NFC-V compatible tag
     * @return NFC-V object
     */
    public static NfcV get(Tag tag) {
        if (!tag.hasTech(TagTechnology.NFC_V)) return null;
        try {
            return new NfcV(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public NfcV(Tag tag) throws RemoteException {
        super(tag, TagTechnology.NFC_V);
        Bundle extras = tag.getTechExtras(TagTechnology.NFC_V);
        mRespFlags = extras.getByte(EXTRA_RESP_FLAGS);
        mDsfId = extras.getByte(EXTRA_DSFID);
    }

    /**
     * Return the Response Flag bytes from tag discovery.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return Response Flag bytes
     */
    public byte getResponseFlags() {
        return mRespFlags;
    }

    /**
     * Return the DSF ID bytes from tag discovery.
     *
     * <p>Does not cause any RF activity and does not block.
     *
     * @return DSF ID bytes
     */
    public byte getDsfId() {
        return mDsfId;
    }

    /**
     * Send raw NFC-V commands to the tag and receive the response.
     *
     * <p>Applications must not append the CRC to the payload,
     * it will be automatically calculated. The application does
     * provide FLAGS, CMD and PARAMETER bytes.
     *
     * <p>Use {@link #getMaxTransceiveLength} to retrieve the maximum amount of bytes
     * that can be sent with {@link #transceive}.
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


    /**
     * Return the maximum number of bytes that can be sent with {@link #transceive}.
     * @return the maximum number of bytes that can be sent with {@link #transceive}.
     */
    public int getMaxTransceiveLength() {
        return getMaxTransceiveLengthInternal();
    }
}
