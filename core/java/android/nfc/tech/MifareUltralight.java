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

//TOOD: Ultralight C 3-DES authentication, one-way counter

/**
 * Technology class representing MIFARE Ultralight and MIFARE Ultralight C tags.
 *
 * <p>Support for this technology type is optional. If the NFC stack doesn't support this technology
 * MIFARE Ultralight class tags will still be scanned, but will only show the NfcA technology.
 *
 * <p>MIFARE Ultralight compatible tags have 4 byte pages. The read command
 * returns 4 pages (16 bytes) at a time, for speed. The write command operates
 * on a single page (4 bytes) to minimize EEPROM write cycles.
 *
 * <p>The original MIFARE Ultralight consists of a 64 byte EEPROM. The first
 * 4 pages are for the OTP area, manufacturer data, and locking bits. They are
 * readable and some bits are writable. The final 12 pages are the user
 * read/write area. For more information see the NXP data sheet MF0ICU1.
 *
 * <p>The MIFARE Ultralight C consists of a 192 byte EEPROM. The first 4 pages
 * are for OTP, manufacturer data, and locking bits. The next 36 pages are the
 * user read/write area. The next 4 pages are additional locking bits, counters
 * and authentication configuration and are readable. The final 4 pages are for
 * the authentication key and are not readable. For more information see the
 * NXP data sheet MF0ICU2.
 */
public final class MifareUltralight extends BasicTagTechnology {
    /** A MIFARE Ultralight compatible tag of unknown type */
    public static final int TYPE_UNKNOWN = -1;
    /** A MIFARE Ultralight tag */
    public static final int TYPE_ULTRALIGHT = 1;
    /** A MIFARE Ultralight C tag */
    public static final int TYPE_ULTRALIGHT_C = 2;

    /** Size of a MIFARE Ultralight page in bytes */
    public static final int PAGE_SIZE = 4;

    private static final int NXP_MANUFACTURER_ID = 0x04;
    private static final int MAX_PAGE_COUNT = 256;

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
            //TODO: stack should use NXP AN1303 procedure to make a best guess
            // attempt at classifying Ultralight vs Ultralight C.
            mType = TYPE_ULTRALIGHT;
        }
    }

    /** Returns the type of the tag.
     * <p>It is very hard to always accurately classify a MIFARE Ultralight
     * compatible tag as Ultralight original or Ultralight C. So consider
     * {@link #getType} a hint. */
    public int getType() {
        return mType;
    }

    // Methods that require connect()
    /**
     * Read 4 pages (16 bytes).
     * <p>The MIFARE Ultralight protocol always reads 4 pages at a time.
     * <p>If the read spans past the last readable block, then the tag will
     * return pages that have been wrapped back to the first blocks. MIFARE
     * Ultralight tags have readable blocks 0x00 through 0x0F. So a read to
     * block offset 0x0E would return blocks 0x0E, 0x0F, 0x00, 0x01. MIFARE
     * Ultralight C tags have readable blocks 0x00 through 0x2B. So a read to
     * block 0x2A would return blocks 0x2A, 0x2B, 0x00, 0x01.
     * <p>This requires that the tag be connected.
     *
     * @return 4 pages (16 bytes)
     * @throws IOException
     */
    public byte[] readPages(int pageOffset) throws IOException {
        validatePageOffset(pageOffset);
        checkConnected();

        byte[] cmd = { 0x30, (byte) pageOffset};
        return transceive(cmd, false);
    }

    /**
     * Write 1 page (4 bytes).
     * <p>The MIFARE Ultralight protocol always writes 1 page at a time.
     * <p>This requires that the tag be connected.
     *
     * @param pageOffset The offset of the page to write
     * @param data The data to write
     * @throws IOException
     */
    public void writePage(int pageOffset, byte[] data) throws IOException {
        validatePageOffset(pageOffset);
        checkConnected();

        byte[] cmd = new byte[data.length + 2];
        cmd[0] = (byte) 0xA2;
        cmd[1] = (byte) pageOffset;
        System.arraycopy(data, 0, cmd, 2, data.length);

        transceive(cmd, false);
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

    private static void validatePageOffset(int pageOffset) {
        // Do not be too strict on upper bounds checking, since some cards
        // may have more addressable memory than they report.
        // Note that issuing a command to an out-of-bounds block is safe - the
        // tag will wrap the read to an addressable area. This validation is a
        // helper to guard against obvious programming mistakes.
        if (pageOffset < 0 || pageOffset >= MAX_PAGE_COUNT) {
            throw new IndexOutOfBoundsException("page out of bounds: " + pageOffset);
        }
    }
}
