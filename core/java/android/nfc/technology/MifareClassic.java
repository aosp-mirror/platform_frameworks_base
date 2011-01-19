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
import android.nfc.TagLostException;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.RemoteException;

import java.io.IOException;

/**
 * Technology class representing MIFARE Classic tags (also known as MIFARE Standard).
 *
 * <p>Support for this technology type is optional. If the NFC stack doesn't support this technology
 * MIFARE Classic tags will still be scanned, but will only show the NfcA technology.
 *
 * <p>MIFARE Classic tags have sectors that each contain blocks. The block size is constant at
 * 16 bytes, but the number of sectors and the sector size varies by product. MIFARE has encryption
 * built in and each sector has two keys associated with it, as well as ACLs to determine what
 * level acess each key grants. Before operating on a sector you must call either
 * {@link #authenticateSector(int, byte[], boolean)} or
 * {@link #authenticateBlock(int, byte[], boolean)} to gain authorize your request.
 */
public final class MifareClassic extends BasicTagTechnology {
    /**
     * The well-known default MIFARE read key. All keys are set to this at the factory.
     * Using this key will effectively make the payload in the sector public.
     */
    public static final byte[] KEY_DEFAULT =
            {(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF};
    /**
     * The well-known, default MIFARE Application Directory read key.
     */
    public static final byte[] KEY_MIFARE_APPLICATION_DIRECTORY =
            {(byte)0xA0,(byte)0xA1,(byte)0xA2,(byte)0xA3,(byte)0xA4,(byte)0xA5};
    /**
     * The well-known, default read key for NDEF data on a MIFARE Classic
     */
    public static final byte[] KEY_NFC_FORUM =
            {(byte)0xD3,(byte)0xF7,(byte)0xD3,(byte)0xF7,(byte)0xD3,(byte)0xF7};

    /** A MIFARE Classic tag */
    public static final int TYPE_CLASSIC = 0;
    /** A MIFARE Plus tag */
    public static final int TYPE_PLUS = 1;
    /** A MIFARE Pro tag */
    public static final int TYPE_PRO = 2;
    /** The tag type is unknown */
    public static final int TYPE_UNKNOWN = 5;

    /** The tag contains 16 sectors, each holding 4 blocks. */
    public static final int SIZE_1K = 1024;
    /** The tag contains 32 sectors, each holding 4 blocks. */
    public static final int SIZE_2K = 2048;
    /**
     * The tag contains 40 sectors. The first 32 sectors contain 4 blocks and the last 8 sectors
     * contain 16 blocks.
     */
    public static final int SIZE_4K = 4096;
    /** The tag contains 5 sectors, each holding 4 blocks. */
    public static final int SIZE_MINI = 320;
    /** The capacity is unknown */
    public static final int SIZE_UNKNOWN = 0;

    private boolean mIsEmulated;
    private int mType;
    private int mSize;

    /** @hide */
    public MifareClassic(NfcAdapter adapter, Tag tag, Bundle extras) throws RemoteException {
        super(adapter, tag, TagTechnology.MIFARE_CLASSIC);

        // Check if this could actually be a MIFARE Classic
        NfcA a = (NfcA) adapter.getTechnology(tag, TagTechnology.NFC_A);

        mIsEmulated = false;
        mType = TYPE_UNKNOWN;
        mSize = SIZE_UNKNOWN;

        switch (a.getSak()) {
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
                    break;
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
        }
    }

    /** Returns the size of the tag, determined at discovery time */
    public int getSize() {
        return mSize;
    }

    /** Returns the size of the tag, determined at discovery time */
    public int getType() {
        return mType;
    }

    /** Returns true if the tag is emulated, determined at discovery time */
    public boolean isEmulated() {
        return mIsEmulated;
    }

    /** Returns the number of sectors on this tag, determined at discovery time */
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

    /** Returns the sector size, determined at discovery time */
    public int getSectorSize(int sector) {
        return getBlockCount(sector) * 16;
    }

    /** Returns the total block count, determined at discovery time */
    public int getTotalBlockCount() {
        int totalBlocks = 0;
        for (int sec = 0; sec < getSectorCount(); sec++) {
            totalBlocks += getSectorSize(sec);
        }

        return totalBlocks;
    }

    /** Returns the block count for the given sector, determined at discovery time */
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
     * Authenticate the entire sector that the given block resides in.
     * <p>This requires a that the tag be connected.
     */
    public boolean authenticateBlock(int block, byte[] key, boolean keyA) throws IOException {
        checkConnected();

        byte[] cmd = new byte[12];

        // First byte is the command
        if (keyA) {
            cmd[0] = 0x60; // phHal_eMifareAuthentA
        } else {
            cmd[0] = 0x61; // phHal_eMifareAuthentB
        }

        // Second byte is block address
        cmd[1] = (byte) block;

        // Next 4 bytes are last 4 bytes of UID
        byte[] uid = getTag().getId();
        System.arraycopy(uid, uid.length - 4, cmd, 2, 4);

        // Next 6 bytes are key
        System.arraycopy(key, 0, cmd, 6, 6);

        try {
            if ((transceive(cmd, false) != null)) {
                return true;
            }
        } catch (TagLostException e) {
            throw e;
        } catch (IOException e) {
            // No need to deal with, will return false anyway
        }
        return false;
    }

    /**
     * Authenticate for a given sector.
     * <p>This requires a that the tag be connected.
     */
    public boolean authenticateSector(int sector, byte[] key, boolean keyA) throws IOException {
        checkConnected();

        byte addr = (byte) ((firstBlockInSector(sector)) & 0xff);

        // Note that authenticating a block of a sector, will authenticate
        // the entire sector.
        return authenticateBlock(addr, key, keyA);
    }

    /**
     * Sector indexing starts at 0.
     * Block indexing starts at 0, and resets in each sector.
     * <p>This requires a that the tag be connected.
     * @throws IOException
     */
    public byte[] readBlock(int sector, int block) throws IOException {
        checkConnected();

        byte addr = (byte) ((firstBlockInSector(sector) + block) & 0xff);
        return readBlock(addr);
    }

    /**
     * Reads absolute block index.
     * <p>This requires a that the tag be connected.
     * @throws IOException
     */
    public byte[] readBlock(int block) throws IOException {
        checkConnected();

        byte addr = (byte) block;
        byte[] blockread_cmd = { 0x30, addr };

        return transceive(blockread_cmd, false);
    }

    /**
     * Writes absolute block index.
     * <p>This requires a that the tag be connected.
     * @throws IOException
     */
    public void writeBlock(int block, byte[] data) throws IOException {
        checkConnected();

        byte addr = (byte) block;
        byte[] blockwrite_cmd = new byte[data.length + 2];
        blockwrite_cmd[0] = (byte) 0xA0; // MF write command
        blockwrite_cmd[1] = addr;
        System.arraycopy(data, 0, blockwrite_cmd, 2, data.length);

        transceive(blockwrite_cmd, false);
    }

    /**
     * Writes relative block in sector.
     * <p>This requires a that the tag be connected.
     * @throws IOException
     */
    public void writeBlock(int sector, int block, byte[] data) throws IOException {
        checkConnected();

        byte addr = (byte) ((firstBlockInSector(sector) + block) & 0xff);

        writeBlock(addr, data);
    }

    public void increment(int block) throws IOException {
        checkConnected();

        byte[] incr_cmd = { (byte) 0xC1, (byte) block };

        transceive(incr_cmd, false);
    }

    public void decrement(int block) throws IOException {
        checkConnected();

        byte[] decr_cmd = { (byte) 0xC0, (byte) block };

        transceive(decr_cmd, false);
    }

    public void transfer(int block) throws IOException {
        checkConnected();

        byte[] trans_cmd = { (byte) 0xB0, (byte) block };

        transceive(trans_cmd, false);
    }

    public void restore(int block) throws IOException {
        checkConnected();

        byte[] rest_cmd = { (byte) 0xC2, (byte) block };

        transceive(rest_cmd, false);
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
