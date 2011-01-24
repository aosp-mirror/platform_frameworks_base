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
import android.os.RemoteException;

import java.io.IOException;

/**
 * Technology class representing MIFARE Ultralight and MIFARE Ultralight C tags.
 *
 * <p>Support for this technology type is optional. If the NFC stack doesn't support this technology
 * MIFARE Ultralight class tags will still be scanned, but will only show the NfcA technology.
 *
 * <p>MIFARE Ultralight class tags have a series of 4 bytes pages that can be individually written
 * and read in chunks of 4 for a total read of 16 bytes.
 */
public final class MifareUltralight extends BasicTagTechnology {
    /** A MIFARE Ultralight tag */
    public static final int TYPE_ULTRALIGHT = 1;
    /** A MIFARE Ultralight C tag */
    public static final int TYPE_ULTRALIGHT_C = 2;
    /** The tag type is unknown */
    public static final int TYPE_UNKNOWN = 10;

    private static final int NXP_MANUFACTURER_ID = 0x04;

    private int mType;

    /**
     * Returns an instance of this tech for the given tag. If the tag doesn't support
     * this tech type null is returned.
     *
     * @param tag The tag to get the tech from
     */
    public static MifareUltralight get(Tag tag) {
        if (!tag.hasTech(TagTechnology.MIFARE_ULTRALIGHT)) return null;
        try {
            return new MifareUltralight(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public MifareUltralight(Tag tag) throws RemoteException {
        super(tag, TagTechnology.MIFARE_ULTRALIGHT);

        // Check if this could actually be a Mifare
        NfcA a = NfcA.get(tag);

        mType = TYPE_UNKNOWN;

        if (a.getSak() == 0x00 && tag.getId()[0] == NXP_MANUFACTURER_ID) {
            // could be UL or UL-C
            mType = TYPE_ULTRALIGHT;
        }
    }

    /** Returns the type of the tag */
    public int getType() {
        return mType;
    }

    // Methods that require connect()
    /**
     * Reads a single 16 byte block from the given page offset.
     *
     * <p>This requires a that the tag be connected.
     *
     * @throws IOException
     */
    public byte[] readBlock(int page) throws IOException {
        checkConnected();

        byte[] blockread_cmd = { 0x30, (byte) page}; // phHal_eMifareRead
        return transceive(blockread_cmd, false);
    }

    /**
     * Writes a 4 byte page to the tag.
     *
     * <p>This requires a that the tag be connected.
     *
     * @param page The offset of the page to write
     * @param data The data to write
     * @throws IOException
     */
    public void writePage(int page, byte[] data) throws IOException {
        checkConnected();

        byte[] pagewrite_cmd = new byte[data.length + 2];
        pagewrite_cmd[0] = (byte) 0xA2;
        pagewrite_cmd[1] = (byte) page;
        System.arraycopy(data, 0, pagewrite_cmd, 2, data.length);

        transceive(pagewrite_cmd, false);
    }

    /**
     * Send raw NfcA data to a tag and receive the response.
     * <p>
     * This method will block until the response is received. It can be canceled
     * with {@link #close}.
     * <p>Requires {@link android.Manifest.permission#NFC} permission.
     * <p>This requires a that the tag be connected.
     *
     * @param data bytes to send
     * @return bytes received in response
     * @throws IOException if the target is lost or connection closed
     */
    public byte[] transceive(byte[] data) throws IOException {
        return transceive(data, true);
    }
}
