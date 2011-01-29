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
import android.nfc.TagLostException;
import android.os.RemoteException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
 * {@link #authenticateSectorWithKeyA(int, byte[])} or
 * {@link #authenticateSectorWithKeyB(int, byte[])} to gain authorization for your request.
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

    /** A Mifare Classic compatible card of unknown type */
    public static final int TYPE_UNKNOWN = -1;
    /** A MIFARE Classic tag */
    public static final int TYPE_CLASSIC = 0;
    /** A MIFARE Plus tag */
    public static final int TYPE_PLUS = 1;
    /** A MIFARE Pro tag */
    public static final int TYPE_PRO = 2;

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

    /** Size of a Mifare Classic block (in bytes) */
    public static final int BLOCK_SIZE = 16;

    private static final int MAX_BLOCK_COUNT = 256;
    private static final int MAX_SECTOR_COUNT = 40;

    private boolean mIsEmulated;
    private int mType;
    private int mSize;

    /**
     * Returns an instance of this tech for the given tag. If the tag doesn't support
     * this tech type null is returned.
     *
     * @param tag The tag to get the tech from
     */
    public static MifareClassic get(Tag tag) {
        if (!tag.hasTech(TagTechnology.MIFARE_CLASSIC)) return null;
        try {
            return new MifareClassic(tag);
        } catch (RemoteException e) {
            return null;
        }
    }

    /** @hide */
    public MifareClassic(Tag tag) throws RemoteException {
        super(tag, TagTechnology.MIFARE_CLASSIC);

        NfcA a = NfcA.get(tag);  // Mifare Classic is always based on NFC a

        mIsEmulated = false;

        switch (a.getSak()) {
        case 0x08:
            mType = TYPE_CLASSIC;
            mSize = SIZE_1K;
            break;
        case 0x09:
            mType = TYPE_CLASSIC;
            mSize = SIZE_MINI;
            break;
        case 0x10:
            mType = TYPE_PLUS;
            mSize = SIZE_2K;
            // SecLevel = SL2
            break;
        case 0x11:
            mType = TYPE_PLUS;
            mSize = SIZE_4K;
            // Seclevel = SL2
            break;
        case 0x18:
            mType = TYPE_CLASSIC;
            mSize = SIZE_4K;
            break;
        case 0x28:
            mType = TYPE_CLASSIC;
            mSize = SIZE_1K;
            mIsEmulated = true;
            break;
        case 0x38:
            mType = TYPE_CLASSIC;
            mSize = SIZE_4K;
            mIsEmulated = true;
            break;
        case 0x88:
            mType = TYPE_CLASSIC;
            mSize = SIZE_1K;
            // NXP-tag: false
            break;
        case 0x98:
        case 0xB8:
            mType = TYPE_PRO;
            mSize = SIZE_4K;
            break;
        default:
            // Stack incorrectly reported a MifareClassic. We cannot handle this
            // gracefully - we have no idea of the memory layout. Bail.
            throw new RuntimeException(
                    "Tag incorrectly enumerated as Mifare Classic, SAK = " + a.getSak());
        }
    }

    /** Returns the type of the tag, determined at discovery time */
    public int getType() {
        return mType;
    }

    /** Returns the size of the tag in bytes, determined at discovery time */
    public int getSize() {
        return mSize;
    }

    /** Returns true if the tag is emulated, determined at discovery time.
     * These are actually smart-cards that emulate a Mifare Classic interface.
     * They can be treated identically to a Mifare Classic tag.
     * @hide
     */
    public boolean isEmulated() {
        return mIsEmulated;
    }

    /** Returns the number of sectors on this tag, determined at discovery time */
    public int getSectorCount() {
        switch (mSize) {
        case SIZE_1K:
            return 16;
        case SIZE_2K:
            return 32;
        case SIZE_4K:
            return 40;
        case SIZE_MINI:
            return 5;
        default:
            return 0;
        }
    }

    /** Returns the total block count, determined at discovery time */
    public int getBlockCount() {
        return mSize / BLOCK_SIZE;
    }

    /** Returns the block count for the given sector, determined at discovery time */
    public int getBlockCountInSector(int sectorIndex) {
        validateSector(sectorIndex);

        if (sectorIndex < 32) {
            return 4;
        } else {
            return 16;
        }
    }

    /** Return the sector index of a given block */
    public int blockToSector(int blockIndex) {
        validateBlock(blockIndex);

        if (blockIndex < 32 * 4) {
            return blockIndex / 4;
        } else {
            return 32 + (blockIndex - 32 * 4) / 16;
        }
    }

    /** Return the first block of a given sector */
    public int sectorToBlock(int sectorIndex) {
        if (sectorIndex < 32) {
            return sectorIndex * 4;
        } else {
            return 32 * 4 + (sectorIndex - 32) * 16;
        }
    }

    // Methods that require connect()
    /**
     * Authenticate a sector.
     * <p>Every sector has an A and B key with different access privileges,
     * this method attempts to authenticate against the A key.
     * <p>This requires a that the tag be connected.
     */
    public boolean authenticateSectorWithKeyA(int sectorIndex, byte[] key) throws IOException {
        return authenticate(sectorIndex, key, true);
    }

    /**
     * Authenticate a sector.
     * <p>Every sector has an A and B key with different access privileges,
     * this method attempts to authenticate against the B key.
     * <p>This requires a that the tag be connected.
     */
    public boolean authenticateSectorWithKeyB(int sectorIndex, byte[] key) throws IOException {
        return authenticate(sectorIndex, key, false);
    }

    private boolean authenticate(int sector, byte[] key, boolean keyA) throws IOException {
        validateSector(sector);
        checkConnected();

        byte[] cmd = new byte[12];

        // First byte is the command
        if (keyA) {
            cmd[0] = 0x60; // phHal_eMifareAuthentA
        } else {
            cmd[0] = 0x61; // phHal_eMifareAuthentB
        }

        // Second byte is block address
        // Authenticate command takes a block address. Authenticating a block
        // of a sector will authenticate the entire sector.
        cmd[1] = (byte) sectorToBlock(sector);

        // Next 4 bytes are last 4 bytes of UID
        byte[] uid = getTag().getId();
        System.arraycopy(uid, uid.length - 4, cmd, 2, 4);

        // Next 6 bytes are key
        System.arraycopy(key, 0, cmd, 6, 6);

        try {
            if (transceive(cmd, false) != null) {
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
     * Read 16-byte block.
     * <p>This requires a that the tag be connected.
     * @throws IOException
     */
    public byte[] readBlock(int blockIndex) throws IOException {
        validateBlock(blockIndex);
        checkConnected();

        byte[] cmd = { 0x30, (byte) blockIndex };
        return transceive(cmd, false);
    }

    /**
     * Write 16-byte block.
     * <p>This requires a that the tag be connected.
     * @throws IOException
     */
    public void writeBlock(int blockIndex, byte[] data) throws IOException {
        validateBlock(blockIndex);
        checkConnected();
        if (data.length != 16) {
            throw new IllegalArgumentException("must write 16-bytes");
        }

        byte[] cmd = new byte[data.length + 2];
        cmd[0] = (byte) 0xA0; // MF write command
        cmd[1] = (byte) blockIndex;
        System.arraycopy(data, 0, cmd, 2, data.length);

        transceive(cmd, false);
    }

    /**
     * Increment a value block, and store the result in temporary memory.
     * @param blockIndex
     * @throws IOException
     */
    public void increment(int blockIndex, int value) throws IOException {
        validateBlock(blockIndex);
        validateValueOperand(value);
        checkConnected();

        ByteBuffer cmd = ByteBuffer.allocate(6);
        cmd.order(ByteOrder.LITTLE_ENDIAN);
        cmd.put( (byte) 0xC1 );
        cmd.put( (byte) blockIndex );
        cmd.putInt(value);

        transceive(cmd.array(), false);
    }

    /**
     * Decrement a value block, and store the result in temporary memory.
     * @param blockIndex
     * @throws IOException
     */
    public void decrement(int blockIndex, int value) throws IOException {
        validateBlock(blockIndex);
        validateValueOperand(value);
        checkConnected();

        ByteBuffer cmd = ByteBuffer.allocate(6);
        cmd.order(ByteOrder.LITTLE_ENDIAN);
        cmd.put( (byte) 0xC0 );
        cmd.put( (byte) blockIndex );
        cmd.putInt(value);

        transceive(cmd.array(), false);
    }

    /**
     * Copy from temporary memory to value block.
     * @param blockIndex
     * @throws IOException
     */
    public void transfer(int blockIndex) throws IOException {
        validateBlock(blockIndex);
        checkConnected();

        byte[] cmd = { (byte) 0xB0, (byte) blockIndex };

        transceive(cmd, false);
    }

    /**
     * Copy from value block to temporary memory.
     * @param blockIndex
     * @throws IOException
     */
    public void restore(int blockIndex) throws IOException {
        validateBlock(blockIndex);
        checkConnected();

        byte[] cmd = { (byte) 0xC2, (byte) blockIndex };

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

    private static void validateSector(int sector) {
        // Do not be too strict on upper bounds checking, since some cards
        // have more addressable memory than they report. For example,
        // Mifare Plus 2k cards will appear as Mifare Classic 1k cards when in
        // Mifare Classic compatibility mode.
        // Note that issuing a command to an out-of-bounds block is safe - the
        // tag should report error causing IOException. This validation is a
        // helper to guard against obvious programming mistakes.
        if (sector < 0 || sector >= MAX_SECTOR_COUNT) {
            throw new IndexOutOfBoundsException("sector out of bounds: " + sector);
        }
    }

    private static void validateBlock(int block) {
        // Just looking for obvious out of bounds...
        if (block < 0 || block >= MAX_BLOCK_COUNT) {
            throw new IndexOutOfBoundsException("block out of bounds: " + block);
        }
    }

    private static void validateValueOperand(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("value operand negative");
        }
    }
}
