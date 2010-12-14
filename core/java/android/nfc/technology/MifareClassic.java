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
 * Concrete class for TagTechnology.MIFARE_CLASSIC
 *
 * Mifare classic has n sectors, with varying sizes, although
 * they are at least the same pattern for any one mifare classic
 * product. Each sector has two keys. Authentication with the correct
 * key is needed before access to any sector.
 *
 * Each sector has k blocks.
 * Block size is constant across the whole mifare classic family.
 */
public final class MifareClassic extends BasicTagTechnology {
    /**
     * The well-known, default MIFARE read key.
     * Use this key to effectively make the payload in this sector
     * public.
     */
    public static final byte[] KEY_DEFAULT =
            {(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF};
    /**
     * The well-known, default Mifare Application Directory read key.
     */
    public static final byte[] KEY_MIFARE_APPLICATION_DIRECTORY =
            {(byte)0xA0,(byte)0xA1,(byte)0xA2,(byte)0xA3,(byte)0xA4,(byte)0xA5};
    /**
     * The well-known, default read key for NDEF data on a Mifare Classic
     */
    public static final byte[] KEY_NFC_FORUM =
            {(byte)0xD3,(byte)0xF7,(byte)0xD3,(byte)0xF7,(byte)0xD3,(byte)0xF7};

    public static final int TYPE_CLASSIC = 0;
    public static final int TYPE_PLUS = 1;
    public static final int TYPE_PRO = 2;
    public static final int TYPE_DESFIRE = 3;
    public static final int TYPE_ULTRALIGHT = 4;
    public static final int TYPE_UNKNOWN = 5;

    public static final int SIZE_1K = 1024;
    public static final int SIZE_2K = 2048;
    public static final int SIZE_4K = 4096;
    public static final int SIZE_MINI = 320;
    public static final int SIZE_UNKNOWN = 0;

    private boolean mIsEmulated;
    private int mType;
    private int mSize;

    public MifareClassic(NfcAdapter adapter, Tag tag, Bundle extras) throws RemoteException {
        super(adapter, tag, TagTechnology.MIFARE_CLASSIC);

        // Check if this could actually be a Mifare
        NfcA a = (NfcA) tag.getTechnology(adapter, TagTechnology.NFC_A);
        //short[] ATQA = getATQA(tag);

        mIsEmulated = false;
        mType = TYPE_UNKNOWN;
        mSize = SIZE_UNKNOWN;

        switch (a.getSak()) {
            case 0x00:
                // could be UL or UL-C
                mType = TYPE_ULTRALIGHT;
                break;
            case 0x08:
                // Type == classic
                // Size = 1K
                mType = TYPE_CLASSIC;
                mSize = SIZE_1K;
                break;
            case 0x09:
                // Type == classic mini
                // Size == ?
                mType = TYPE_CLASSIC;
                mSize = SIZE_MINI;
                break;
            case 0x10:
                // Type == MF+
                // Size == 2K
                // SecLevel = SL2
                mType = TYPE_PLUS;
                mSize = SIZE_2K;
                break;
            case 0x11:
                // Type == MF+
                // Size == 4K
                // Seclevel = SL2
                mType = TYPE_PLUS;
                mSize = SIZE_4K;
                break;
            case 0x18:
                // Type == classic
                // Size == 4k
                mType = TYPE_CLASSIC;
                mSize = SIZE_4K;
                break;
            case 0x20:
                // TODO this really should be a short, not byte
                if (a.getAtqa()[0] == 0x03) {
                    // Type == DESFIRE
                    mType = TYPE_DESFIRE;
                } else {
                    // Type == MF+
                    // SL = SL3
                    mType = TYPE_PLUS;
                    mSize = SIZE_UNKNOWN;
                }
                break;
            case 0x28:
                // Type == MF Classic
                // Size == 1K
                // Emulated == true
                mType = TYPE_CLASSIC;
                mSize = SIZE_1K;
                mIsEmulated = true;
                break;
            case 0x38:
                // Type == MF Classic
                // Size == 4K
                // Emulated == true
                mType = TYPE_CLASSIC;
                mSize = SIZE_4K;
                mIsEmulated = true;
                break;
            case 0x88:
                // Type == MF Classic
                // Size == 1K
                // NXP-tag: false
                mType = TYPE_CLASSIC;
                mSize = SIZE_1K;
                break;
            case 0x98:
            case 0xB8:
                // Type == MF Pro
                // Size == 4K
                mType = TYPE_PRO;
                mSize = SIZE_4K;
                break;
            default:
                // Unknown, not MIFARE
                break;
        }
    }

    // Immutable data known at discovery time
    public int getSize() {
        return mSize;
    }

    public int getType() {
        return mType;
    }

    public boolean isEmulated() {
        return mIsEmulated;
    }

    public int getSectorCount() {
        switch (mSize) {
            case SIZE_1K: {
                return 16;
            }
            case SIZE_2K: {
                return 32;
            }
            case SIZE_4K: {
                return 40;
            }
            case SIZE_MINI: {
                return 5;
            }
            default: {
                return 0;
            }
        }
    }

    public int getSectorSize(int sector) {
        return getBlockCount(sector) * 16;
    }

    public int getBlockCount(int sector) {
        if (sector >= getSectorCount()) {
            throw new IllegalArgumentException("this card only has " + getSectorCount() +
                    " sectors");
        }

        if (sector <= 32) {
            return 4;
        } else {
            return 16;
        }
    }

    private byte firstBlockInSector(int sector) {
        if (sector < 32) {
            return (byte) ((sector * 4) & 0xff);
        } else {
            return (byte) ((32 * 4 + ((sector - 32) * 16)) & 0xff);
        }
    }

    // Methods that require connect()
    /**
     * Authenticate for a given sector.
     */
    public boolean authenticateSector(int sector, byte[] key, boolean keyA) {
        byte[] cmd = new byte[12];

        // First byte is the command
        if (keyA) {
            cmd[0] = 0x60; // phHal_eMifareAuthentA
        } else {
            cmd[0] = 0x61; // phHal_eMifareAuthentB
        }

        // Second byte is block address
        cmd[1] = firstBlockInSector(sector);

        // Next 4 bytes are last 4 bytes of UID
        byte[] uid = getTag().getId();
        System.arraycopy(uid, uid.length - 4, cmd, 2, 4);

        // Next 6 bytes are key
        System.arraycopy(key, 0, cmd, 6, 6);

        try {
            if ((transceive(cmd) != null)) {
                return true;
            }
        } catch (IOException e) {
            // No need to deal with, will return false anyway
        }
        return false;
    }

    /**
     * Sector indexing starts at 0.
     * Block indexing starts at 0, and resets in each sector.
     * @throws IOException
     */
    public byte[] readBlock(int sector, int block) throws IOException {
        byte addr = (byte) ((firstBlockInSector(sector) + block) & 0xff);
        byte[] blockread_cmd = { 0x30, addr }; // phHal_eMifareRead

        // TODO deal with authentication problems
        return transceive(blockread_cmd);
    }

//    public byte[] readSector(int sector);
    //TODO: define an enumeration for access control settings
//    public int readSectorAccessControl(int sector);

    /**
     * @throws IOException
     * @throws NotAuthenticatedException
     */
/*
    public void writeBlock(int block, byte[] data);
    public void writeSector(int block, byte[] sector);
    public void writeSectorAccessControl(int sector, int access);
    public void increment(int block);
    public void decrement(int block);

*/
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
    @Override
    public byte[] transceive(byte[] data) throws IOException {
        try {
            byte[] response = mTagService.transceive(mTag.getServiceHandle(), data, false);
            if (response == null) {
                throw new IOException("transceive failed");
            }
            return response;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            throw new IOException("NFC service died");
        }
    }
}
