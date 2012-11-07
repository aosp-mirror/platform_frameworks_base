/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 *   HOST CARD EMULATION PATCH 0.01
 *   Author:  doug yeager (doug@simplytapp.com)
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

import android.nfc.ErrorCodes;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * Provides access to ISO-PCD type A (ISO 14443-4) properties and I/O operations on a {@link Tag}.
 *
 * <p>Acquire an {@link IsoPcdA} object using {@link #get}.
 * <p>The primary ISO-PCD type A I/O operation is {@link #transceive}. Applications must
 * implement their own protocol stack on top of {@link #transceive}.
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
 * @hide
 */
public final class IsoPcdA extends BasicTagTechnology {

    /**
     * Get an instance of {@link IsoPcdA} for the given tag.
     * <p>Does not cause any RF activity and does not block.
     * <p>Returns null if {@link IsoPcdA} was not enumerated in {@link Tag#getTechList}.
     * This indicates the tag does not support ISO-PCD type A.
     *
     * @param tag an ISO-PCD type A compatible PCD
     * @return ISO-PCD type A object
     */
    public static IsoPcdA get(Tag tag) {
        if (!tag.hasTech(TagTechnology.ISO_PCD_A)) return null;
        try {
            return new IsoPcdA(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public IsoPcdA(Tag tag)
            throws RemoteException {
        super(tag, TagTechnology.ISO_PCD_A);
        Bundle extras = tag.getTechExtras(TagTechnology.ISO_PCD_A);
    }

    /**
     * Send raw ISO-PCD type A data to the PCD and receive the response.
     *
     * <p>Applications must only send the INF payload, and not the start of frame and
     * end of frame indicators. Applications do not need to fragment the payload, it
     * will be automatically fragmented and defragmented by {@link #transceive} if
     * it exceeds FSD/FSC limits.
     *
     * <p>Use {@link #getMaxTransceiveLength} to retrieve the maximum number of bytes
     * that can be sent with {@link #transceive}.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param data  - on the first call to transceive after PCD activation, the data sent to the method will be ignored
     * @return response bytes received, will not be null
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
