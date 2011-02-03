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
import android.util.Log;

import java.io.IOException;

/**
 * Provides access to ISO-DEP (ISO 14443-4) properties and I/O operations on a {@link Tag}.
 *
 * <p>Acquire an {@link IsoDep} object using {@link #get}.
 * <p>The primary ISO-DEP I/O operation is {@link #transceive}. Applications must
 * implement their own protocol stack on top of {@link #transceive}.
 * <p>Tags that enumerate the {@link IsoDep} technology in {@link Tag#getTechList}
 * will also enumerate
 * {@link NfcA} or {@link NfcB} (since IsoDep builds on top of either of these).
 *
 * <p class="note"><strong>Note:</strong> Methods that perform I/O operations
 * require the {@link android.Manifest.permission#NFC} permission.
 */
public final class IsoDep extends BasicTagTechnology {
    private static final String TAG = "NFC";

    /** @hide */
    public static final String EXTRA_HI_LAYER_RESP = "hiresp";
    /** @hide */
    public static final String EXTRA_HIST_BYTES = "histbytes";

    private byte[] mHiLayerResponse = null;
    private byte[] mHistBytes = null;

    /**
     * Get an instance of {@link IsoDep} for the given tag.
     * <p>Does not cause any RF activity and does not block.
     * <p>Returns null if {@link IsoDep} was not enumerated in {@link Tag#getTechList}.
     * This indicates the tag does not support ISO-DEP.
     *
     * @param tag an ISO-DEP compatible tag
     * @return ISO-DEP object
     */
    public static IsoDep get(Tag tag) {
        if (!tag.hasTech(TagTechnology.ISO_DEP)) return null;
        try {
            return new IsoDep(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public IsoDep(Tag tag)
            throws RemoteException {
        super(tag, TagTechnology.ISO_DEP);
        Bundle extras = tag.getTechExtras(TagTechnology.ISO_DEP);
        if (extras != null) {
            mHiLayerResponse = extras.getByteArray(EXTRA_HI_LAYER_RESP);
            mHistBytes = extras.getByteArray(EXTRA_HIST_BYTES);
        }
    }

    /**
     * Set the timeout of {@link #transceive} in milliseconds.
     * <p>The timeout only applies to ISO-DEP {@link #transceive}, and is
     * reset to a default value when {@link #close} is called.
     * <p>Setting a longer timeout may be useful when performing
     * transactions that require a long processing time on the tag
     * such as key generation.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param timeout timeout value in milliseconds
     */
    public void setTimeout(int timeout) {
        try {
            mTag.getTagService().setIsoDepTimeout(timeout);
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            mTag.getTagService().resetIsoDepTimeout();
        } catch (RemoteException e) {
            Log.e(TAG, "NFC service dead", e);
        }
        super.close();
    }

    /**
     * Return the ISO-DEP historical bytes for {@link NfcA} tags.
     * <p>Does not cause any RF activity and does not block.
     * <p>The historical bytes can be used to help identify a tag. They are present
     * only on {@link IsoDep} tags that are based on {@link NfcA} RF technology.
     * If this tag is not {@link NfcA} then null is returned.
     * <p>In ISO 14443-4 terminology, the historical bytes are a subset of the RATS
     * response.
     *
     * @return ISO-DEP historical bytes, or null if this is not a {@link NfcA} tag
     */
    public byte[] getHistoricalBytes() {
        return mHistBytes;
    }

    /**
     * Return the higher layer response bytes for {@link NfcB} tags.
     * <p>Does not cause any RF activity and does not block.
     * <p>The higher layer response bytes can be used to help identify a tag.
     * They are present only on {@link IsoDep} tags that are based on {@link NfcB}
     * RF technology. If this tag is not {@link NfcB} then null is returned.
     * <p>In ISO 14443-4 terminology, the higher layer bytes are a subset of the
     * ATTRIB response.
     *
     * @return ISO-DEP historical bytes, or null if this is not a {@link NfcB} tag
     */
    public byte[] getHiLayerResponse() {
        return mHiLayerResponse;
    }

    /**
     * Send raw ISO-DEP data to the tag and receive the response.
     *
     * <p>Applications must only send the INF payload, and not the start of frame and
     * end of frame indicators. Applications do not need to fragment the payload, it
     * will be automatically fragmented and defragmented by {@link #transceive} if
     * it exceeds FSD/FSC limits.
     *
     * <p>This is an I/O operation and will block until complete. It must
     * not be called from the main application thread. A blocked call will be canceled with
     * {@link IOException} if {@link #close} is called from another thread.
     *
     * <p class="note">Requires the {@link android.Manifest.permission#NFC} permission.
     *
     * @param data command bytes to send, must not be null
     * @return response bytes received, will not be null
     * @throws TagLostException if the tag leaves the field
     * @throws IOException if there is an I/O failure, or this operation is canceled
     */
    public byte[] transceive(byte[] data) throws IOException {
        return transceive(data, true);
    }
}
